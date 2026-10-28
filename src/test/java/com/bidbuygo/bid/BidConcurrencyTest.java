package com.bidbuygo.bid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.Race;
import com.bidbuygo.support.TestAuth;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransactionRepository;
import com.bidbuygo.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class BidConcurrencyTest {

    @Autowired BidService bidService;
    @Autowired BidRepository bidRepository;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;

    private Long sellerId;

    @BeforeEach
    void setUp() {
        sellerId = userRepository.findByEmail(TestAuth.VERIFIED_SELLER).orElseThrow().getId();
    }

    @Test
    void tenBuyersBiddingTheSameAmountAtOnceProduceExactlyOneLeader() throws Exception {
        long auctionId = Fixtures.auctionListing(listingService, sellerId);
        List<User> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buyers.add(Fixtures.fundedUser(userService, walletService, Role.BUYER, 50_000));
        }
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        List<Integer> statuses = Race.run(buyers.stream()
                .map(b -> (Callable<Integer>) () -> Race.status(() -> bidService.placeBid(auctionId, b.getId(), 20_000)))
                .toList());

        assertThat(statuses).filteredOn(s -> s == Race.OK).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409 || s == 400).hasSize(9);

        Listing listing = listingRepository.findById(auctionId).orElseThrow();
        assertThat(listing.getCurrentHighBid()).isEqualTo(20_000);
        assertThat(listing.getBidCount()).isEqualTo(1);
        assertThat(bidRepository.countByListingId(auctionId)).isEqualTo(1);
        assertThat(buyers).filteredOn(b -> wallet(b).getHeldBalance() == 20_000).hasSize(1)
                .allSatisfy(b -> assertThat(b.getId()).isEqualTo(listing.getHighBidderId()));

        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    @Test
    void oneBuyerBiddingOnTwoAuctionsAtOnceCanOnlyFundOne() throws Exception {
        User buyer = Fixtures.fundedUser(userService, walletService, Role.BUYER, 30_000);
        long first = Fixtures.auctionListing(listingService, sellerId);
        long second = Fixtures.auctionListing(listingService, sellerId);
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        List<Integer> statuses = Race.run(List.<Callable<Integer>>of(
                () -> Race.status(() -> bidService.placeBid(first, buyer.getId(), 20_000)),
                () -> Race.status(() -> bidService.placeBid(second, buyer.getId(), 20_000))));

        assertThat(statuses).containsExactlyInAnyOrder(Race.OK, 400);
        Wallet wallet = wallet(buyer);
        assertThat(wallet.getAvailableBalance()).isEqualTo(10_000).isNotNegative();
        assertThat(wallet.getHeldBalance()).isEqualTo(20_000);
        assertThat(List.of(first, second))
                .filteredOn(id -> buyer.getId().equals(listingRepository.findById(id).orElseThrow().getHighBidderId()))
                .hasSize(1);

        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    private Wallet wallet(User user) {
        return walletRepository.findByUserId(user.getId()).orElseThrow();
    }
}
