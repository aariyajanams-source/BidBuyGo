package com.bidbuygo.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.bidbuygo.bid.BidService;
import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.order.OrderRepository;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.IntegrationTest;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.support.Race;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class SettlementConcurrencyTest {

    private static final int BIDDERS = 20;
    private static final int BIDS_PER_BIDDER = 10;

    @Autowired MutableClock clock;
    @Autowired AuctionSettlementService settlementService;
    @Autowired BidService bidService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;
    @Autowired OrderRepository orderRepository;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void biddingWhileTheAuctionClosesEndsInOneConsistentState() throws Exception {
        User seller = Fixtures.trustedSeller(userService, userRepository);
        // Ends inside the anti-sniping window, so accepted bids keep pushing the end out while the closer chases it.
        long auctionId = Fixtures.auctionListing(listingService, seller.getId(), clock.instant().plusSeconds(20));
        List<User> bidders = new ArrayList<>();
        for (int i = 0; i < BIDDERS; i++) {
            bidders.add(Fixtures.fundedUser(userService, walletService, Role.BUYER, 2_000_000));
        }
        long moneyBefore = Fixtures.totalMoney(walletRepository);
        AtomicInteger accepted = new AtomicInteger();

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (User bidder : bidders) {
            tasks.add(() -> {
                for (int i = 0; i < BIDS_PER_BIDDER; i++) {
                    Listing current = listingRepository.findById(auctionId).orElseThrow();
                    if (current.getStatus() != ListingStatus.ACTIVE) {
                        break;
                    }
                    long base = current.getCurrentHighBid() == null ? 20_000 : current.getCurrentHighBid() + 1_000;
                    long amount = base + 1_000L * ThreadLocalRandom.current().nextInt(3);
                    if (Race.status(() -> bidService.placeBid(auctionId, bidder.getId(), amount)) == Race.OK) {
                        accepted.incrementAndGet();
                    }
                }
                return 0;
            });
        }
        tasks.add(() -> {
            // Let some bids land, then keep jumping the clock past the (possibly just extended) end and closing.
            while (accepted.get() < 10) {
                Thread.sleep(5);
            }
            for (int attempt = 0; attempt < 200; attempt++) {
                Instant endsAt = listingRepository.findById(auctionId).orElseThrow().getEndsAt();
                Duration untilEnd = Duration.between(clock.instant(), endsAt);
                if (!untilEnd.isNegative()) {
                    clock.advance(untilEnd.plusMillis(1));
                }
                Optional<CloseReason> outcome = settlementService.closeIfDue(auctionId);
                if (outcome.isPresent()) {
                    return 1;
                }
            }
            return 0;
        });

        List<Integer> results = Race.run(tasks);

        assertThat(results.get(BIDDERS)).as("closer settled the auction").isEqualTo(1);
        Listing listing = listingRepository.findById(auctionId).orElseThrow();
        assertThat(listing.getStatus()).isIn(ListingStatus.SOLD, ListingStatus.CLOSED);
        if (listing.getStatus() == ListingStatus.SOLD) {
            assertThat(listing.getCloseReason()).isEqualTo(CloseReason.SOLD);
            assertThat(orderRepository.findByListingId(auctionId)).isPresent();
            assertThat(wallet(seller).getAvailableBalance()).isEqualTo(listing.getCurrentHighBid());
        } else {
            assertThat(listing.getCloseReason()).isIn(CloseReason.RESERVE_NOT_MET, CloseReason.NO_BIDS);
            assertThat(orderRepository.findByListingId(auctionId)).isEmpty();
        }
        assertThat(orderRepository.findAll()).filteredOn(o -> o.getListingId() == auctionId).hasSizeLessThanOrEqualTo(1);

        for (User bidder : bidders) {
            Wallet wallet = wallet(bidder);
            assertThat(wallet.getAvailableBalance()).isNotNegative();
            assertThat(wallet.getHeldBalance()).as("nothing left held for %s", bidder.getEmail()).isZero();
        }
        assertThat(Fixtures.heldForListing(transactionRepository, auctionId)).isZero();
        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
        assertThat(settlementService.closeIfDue(auctionId)).isEmpty();
    }

    private Wallet wallet(User user) {
        return walletRepository.findByUserId(user.getId()).orElseThrow();
    }
}
