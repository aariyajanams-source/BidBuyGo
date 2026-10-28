package com.bidbuygo.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import com.bidbuygo.bid.BidService;
import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.order.Order;
import com.bidbuygo.order.OrderRepository;
import com.bidbuygo.order.OrderType;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.IntegrationTest;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransaction;
import com.bidbuygo.wallet.WalletTransactionRepository;
import com.bidbuygo.wallet.WalletTransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AuctionSettlementTest {

    // Fixtures.auctionListing: start ₹200.00, reserve ₹500.00.
    private static final long RESERVE = 50_000;

    @Autowired MockMvc mvc;
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

    private User seller;
    private User alice;
    private User bob;
    private long auctionId;

    @BeforeEach
    void setUp() {
        seller = Fixtures.trustedSeller(userService, userRepository);
        alice = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        bob = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        auctionId = Fixtures.auctionListing(listingService, seller.getId());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void reserveMetCapturesWinnerCreditsSellerAndRecordsTheSale() throws Exception {
        bidService.placeBid(auctionId, alice.getId(), 30_000);
        bidService.placeBid(auctionId, bob.getId(), RESERVE);
        long moneyBefore = Fixtures.totalMoney(walletRepository);
        endAuction();

        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.SOLD);

        assertWallet(bob, 100_000 - RESERVE, 0);
        assertWallet(alice, 100_000, 0);
        assertWallet(seller, RESERVE, 0);
        assertThat(ledger(bob)).last().extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getHeldDelta, WalletTransaction::getListingId)
                .containsExactly(WalletTransactionType.CAPTURE, 0L, -RESERVE, auctionId);
        assertThat(ledger(seller)).extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getListingId)
                .containsExactly(tuple(WalletTransactionType.CREDIT, RESERVE, auctionId));

        Order order = orderRepository.findByListingId(auctionId).orElseThrow();
        assertThat(order.getType()).isEqualTo(OrderType.AUCTION_WIN);
        assertThat(order.getBuyerId()).isEqualTo(bob.getId());
        assertThat(order.getSellerId()).isEqualTo(seller.getId());
        assertThat(order.getAmountPaise()).isEqualTo(RESERVE);

        Listing listing = listing();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(listing.getCloseReason()).isEqualTo(CloseReason.SOLD);
        assertThat(userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders()).isEqualTo(11);
        mvc.perform(get("/listings/{id}", auctionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOLD"))
                .andExpect(jsonPath("$.closeReason").value("SOLD"));

        assertSettledCleanly(moneyBefore);
    }

    @Test
    void reserveNotMetReleasesTheWinnersHoldInFull() {
        bidService.placeBid(auctionId, alice.getId(), 30_000);
        long moneyBefore = Fixtures.totalMoney(walletRepository);
        endAuction();

        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.RESERVE_NOT_MET);

        assertWallet(alice, 100_000, 0);
        assertWallet(seller, 0, 0);
        assertThat(ledger(alice)).last().extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getHeldDelta, WalletTransaction::getListingId)
                .containsExactly(WalletTransactionType.RELEASE, 30_000L, -30_000L, auctionId);
        assertThat(orderRepository.findByListingId(auctionId)).isEmpty();
        Listing listing = listing();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.CLOSED);
        assertThat(listing.getCloseReason()).isEqualTo(CloseReason.RESERVE_NOT_MET);
        assertThat(userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders()).isEqualTo(10);

        assertSettledCleanly(moneyBefore);
    }

    @Test
    void noBidsClosesWithNoBidsAndTouchesNoMoney() {
        long moneyBefore = Fixtures.totalMoney(walletRepository);
        long ledgerRowsBefore = transactionRepository.count();
        endAuction();

        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.NO_BIDS);

        Listing listing = listing();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.CLOSED);
        assertThat(listing.getCloseReason()).isEqualTo(CloseReason.NO_BIDS);
        assertThat(transactionRepository.count()).isEqualTo(ledgerRowsBefore);
        assertThat(orderRepository.findByListingId(auctionId)).isEmpty();
        assertThat(userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders()).isEqualTo(10);
        assertSettledCleanly(moneyBefore);
    }

    @Test
    void auctionThatHasNotEndedIsLeftAlone() {
        bidService.placeBid(auctionId, alice.getId(), RESERVE);
        long ledgerRowsBefore = transactionRepository.count();

        assertThat(settlementService.closeIfDue(auctionId)).isEmpty();

        Listing listing = listing();
        assertThat(listing.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(listing.getCloseReason()).isNull();
        assertWallet(alice, 100_000 - RESERVE, RESERVE);
        assertThat(transactionRepository.count()).isEqualTo(ledgerRowsBefore);
        assertThat(orderRepository.findByListingId(auctionId)).isEmpty();
    }

    @Test
    void closingTwiceSettlesExactlyOnce() {
        bidService.placeBid(auctionId, bob.getId(), 60_000);
        long moneyBefore = Fixtures.totalMoney(walletRepository);
        endAuction();

        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.SOLD);
        Wallet bobAfterFirst = wallet(bob);
        Wallet sellerAfterFirst = wallet(seller);
        long ledgerRowsAfterFirst = transactionRepository.count();

        assertThat(settlementService.closeIfDue(auctionId)).isEmpty();

        assertThat(orderRepository.findAll()).filteredOn(o -> o.getListingId() == auctionId).hasSize(1);
        assertThat(ledger(bob)).filteredOn(t -> t.getType() == WalletTransactionType.CAPTURE).hasSize(1);
        assertThat(ledger(seller)).filteredOn(t -> t.getType() == WalletTransactionType.CREDIT).hasSize(1);
        assertThat(transactionRepository.count()).isEqualTo(ledgerRowsAfterFirst);
        assertWallet(bob, bobAfterFirst.getAvailableBalance(), bobAfterFirst.getHeldBalance());
        assertWallet(seller, sellerAfterFirst.getAvailableBalance(), sellerAfterFirst.getHeldBalance());
        assertThat(userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders()).isEqualTo(11);
        assertSettledCleanly(moneyBefore);
    }

    @Test
    void fixedListingIsNeverSettled() {
        long fixedId = Fixtures.fixedListing(listingService, seller.getId(), 10_000);
        clock.advance(Duration.ofDays(30));

        assertThat(settlementService.closeIfDue(fixedId)).isEmpty();
        assertThat(listingRepository.findById(fixedId).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    private void endAuction() {
        clock.advance(Duration.between(clock.instant(), listing().getEndsAt()).plusSeconds(1));
    }

    /** Conservation, ledger replay, nothing left held for this auction, and the hold invariant for the rest. */
    private void assertSettledCleanly(long moneyBefore) {
        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        assertThat(Fixtures.heldForListing(transactionRepository, auctionId)).isZero();
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
    }

    private void assertWallet(User user, long available, long held) {
        Wallet wallet = wallet(user);
        assertThat(wallet.getAvailableBalance()).as("available of %s", user.getEmail()).isEqualTo(available);
        assertThat(wallet.getHeldBalance()).as("held of %s", user.getEmail()).isEqualTo(held);
    }

    private Listing listing() {
        return listingRepository.findById(auctionId).orElseThrow();
    }

    private Wallet wallet(User user) {
        return walletRepository.findByUserId(user.getId()).orElseThrow();
    }

    private List<WalletTransaction> ledger(User user) {
        return transactionRepository.findByWalletIdOrderByIdAsc(wallet(user).getId());
    }
}
