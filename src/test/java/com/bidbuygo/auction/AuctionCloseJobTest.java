package com.bidbuygo.auction;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import com.bidbuygo.bid.BidService;
import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.IntegrationTest;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

@IntegrationTest
class AuctionCloseJobTest {

    @Autowired ApplicationContext context;
    @Autowired MutableClock clock;
    @Autowired AuctionCloseJob job;
    @Autowired AuctionSettlementService settlementService;
    @Autowired BidService bidService;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void schedulerIsDisabledInTests() {
        assertThat(context.getBeanNamesForType(AuctionCloseScheduler.class)).isEmpty();
    }

    @Test
    void oneFailingAuctionDoesNotStopTheOthersFromSettling() {
        Instant now = clock.instant();
        // "broken" ends first, so the job hits the failure before reaching the others.
        User brokenSeller = Fixtures.trustedSeller(userService, userRepository);
        long broken = Fixtures.auctionListing(listingService, brokenSeller.getId(), now.plusSeconds(3));
        long sold = Fixtures.auctionListing(listingService, Fixtures.trustedSeller(userService, userRepository).getId(),
                now.plusSeconds(10));
        long unsold = Fixtures.auctionListing(listingService, Fixtures.trustedSeller(userService, userRepository).getId(),
                now.plusSeconds(50));

        User carol = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        User dave = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        bidService.placeBid(broken, carol.getId(), 50_000);
        bidService.placeBid(sold, dave.getId(), 60_000);
        // Settling "broken" needs the seller's wallet; without it lockWallets throws and that transaction rolls back.
        walletRepository.delete(walletRepository.findByUserId(brokenSeller.getId()).orElseThrow());
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        clock.advance(Duration.ofMinutes(2)); // past every end time, including anti-sniping extensions
        AuctionCloseJob.Result result = job.runOnce();

        assertThat(result.failed()).containsExactly(broken);
        assertThat(result.settled()).contains(sold, unsold);
        assertThat(result.settled().indexOf(sold)).isLessThan(result.settled().indexOf(unsold));
        assertThat(listing(sold).getCloseReason()).isEqualTo(CloseReason.SOLD);
        assertThat(listing(unsold).getCloseReason()).isEqualTo(CloseReason.NO_BIDS);

        // The failed auction is untouched: still ACTIVE, the bid still held.
        Listing failed = listing(broken);
        assertThat(failed.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(failed.getHighBidderId()).isEqualTo(carol.getId());
        assertThat(Fixtures.heldForListing(transactionRepository, broken)).isEqualTo(50_000);
        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);

        // Repair it; the next pass settles it, and nothing is left held.
        walletService.createFor(brokenSeller.getId());
        AuctionCloseJob.Result retry = job.runOnce();
        assertThat(retry.settled()).contains(broken);
        assertThat(retry.failed()).isEmpty();
        assertThat(listing(broken).getCloseReason()).isEqualTo(CloseReason.SOLD);
        assertThat(Fixtures.heldForListing(transactionRepository, broken)).isZero();
        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    @Test
    void secondPassFindsNothingLeftToDo() {
        Instant now = clock.instant();
        long id = Fixtures.auctionListing(listingService, Fixtures.trustedSeller(userService, userRepository).getId(),
                now.plusSeconds(5));
        clock.advance(Duration.ofSeconds(10));

        assertThat(job.runOnce().settled()).contains(id);
        AuctionCloseJob.Result second = job.runOnce();
        assertThat(second.settled()).doesNotContain(id);
        assertThat(second.failed()).doesNotContain(id);
    }

    private Listing listing(long id) {
        return listingRepository.findById(id).orElseThrow();
    }
}
