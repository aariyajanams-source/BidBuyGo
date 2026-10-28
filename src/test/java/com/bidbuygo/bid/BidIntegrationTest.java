package com.bidbuygo.bid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.support.TestAuth;
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
import com.bidbuygo.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class BidIntegrationTest {

    // Fixtures.auctionListing: startPrice ₹200.00, reserve ₹500.00, ends in 1 day. Default min increment is ₹10.00.
    private static final long START = 20_000;
    private static final long INCREMENT = 1_000;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MutableClock clock;
    @Autowired BidService bidService;
    @Autowired BidRepository bidRepository;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;

    private User seller;
    private User alice;
    private User bob;
    private long auctionId;

    @BeforeEach
    void setUp() {
        seller = userRepository.findByEmail(TestAuth.VERIFIED_SELLER).orElseThrow();
        alice = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        bob = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        auctionId = Fixtures.auctionListing(listingService, seller.getId());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void firstBidAtExactlyStartPriceIsAcceptedAndHeld() throws Exception {
        bid(alice, auctionId, START)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPaise").value(START))
                .andExpect(jsonPath("$.bidder").value(BidResponse.maskEmail(alice.getEmail())));

        Listing listing = listing();
        assertThat(listing.getCurrentHighBid()).isEqualTo(START);
        assertThat(listing.getHighBidderId()).isEqualTo(alice.getId());
        assertThat(listing.getBidCount()).isEqualTo(1);
        assertWallet(alice, 100_000 - START, START);
        assertThat(ledger(alice)).extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getHeldDelta, WalletTransaction::getListingId)
                .containsExactly(
                        tuple(WalletTransactionType.DEPOSIT, 100_000L, 0L, null),
                        tuple(WalletTransactionType.HOLD, -START, START, auctionId));
        assertInvariants();
    }

    @Test
    void firstBidBelowStartPriceIsRejectedAndChangesNothing() throws Exception {
        Snapshot before = snapshot();

        bid(alice, auctionId, START - 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bid too low"))
                .andExpect(jsonPath("$.reasons[0]").value("Bid must be at least ₹200.00 (the starting price)"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void laterBidsMustBeatCurrentBidByTheMinimumIncrement() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        Snapshot before = snapshot();

        bid(bob, auctionId, START + INCREMENT - 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]")
                        .value("Bid must be at least ₹210.00 (current bid plus the minimum increment)"));
        assertThat(snapshot()).isEqualTo(before);

        bid(bob, auctionId, START + INCREMENT).andExpect(status().isCreated());
    }

    @Test
    void outbidReleasesThePreviousLeaderInFullAndHoldsTheNewBid() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        bid(bob, auctionId, 25_000).andExpect(status().isCreated());

        assertWallet(alice, 100_000, 0);
        assertWallet(bob, 75_000, 25_000);
        assertThat(ledger(alice)).extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getHeldDelta, WalletTransaction::getListingId)
                .containsExactly(
                        tuple(WalletTransactionType.DEPOSIT, 100_000L, 0L, null),
                        tuple(WalletTransactionType.HOLD, -START, START, auctionId),
                        tuple(WalletTransactionType.RELEASE, START, -START, auctionId));
        assertThat(ledger(bob)).extracting(WalletTransaction::getType, WalletTransaction::getHeldDelta)
                .containsExactly(tuple(WalletTransactionType.DEPOSIT, 0L), tuple(WalletTransactionType.HOLD, 25_000L));
        assertThat(listing().getHighBidderId()).isEqualTo(bob.getId());
        assertInvariants();
    }

    @Test
    void leaderRaisingTheirOwnBidHoldsOnlyTheDifference() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        bid(alice, auctionId, 30_000).andExpect(status().isCreated());

        assertWallet(alice, 70_000, 30_000);
        assertThat(ledger(alice)).extracting(WalletTransaction::getType, WalletTransaction::getAmount)
                .containsExactly(
                        tuple(WalletTransactionType.DEPOSIT, 100_000L),
                        tuple(WalletTransactionType.HOLD, START),
                        tuple(WalletTransactionType.HOLD, 10_000L));
        assertThat(listing().getBidCount()).isEqualTo(2);
        assertInvariants();
    }

    @Test
    void leaderRaiseStillNeedsTheMinimumIncrement() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());

        bid(alice, auctionId, START + INCREMENT - 1).andExpect(status().isBadRequest());
        bid(alice, auctionId, START + INCREMENT).andExpect(status().isCreated());
    }

    @Test
    void leaderRaiseIsCheckedAgainstTheDifferenceOnly() throws Exception {
        User carol = Fixtures.fundedUser(userService, walletService, Role.BUYER, 25_000);
        bid(carol, auctionId, START).andExpect(status().isCreated());

        // Needs 30000 - 20000 = 10000 more, but only 5000 is available.
        bid(carol, auctionId, 30_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]")
                        .value("This bid needs ₹100.00 available but your available balance is ₹50.00"));
        // 25000 needs 5000 more: exactly affordable.
        bid(carol, auctionId, 25_000).andExpect(status().isCreated());
        assertWallet(carol, 0, 25_000);
    }

    @Test
    void insufficientFundsIsRejectedWithAmountAndBalanceAndChangesNothing() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        User poor = Fixtures.fundedUser(userService, walletService, Role.BUYER, 10_000);
        Snapshot before = snapshot();

        bid(poor, auctionId, 25_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient funds"))
                .andExpect(jsonPath("$.reasons[0]")
                        .value("This bid needs ₹250.00 available but your available balance is ₹100.00"));

        assertThat(snapshot()).isEqualTo(before);
        assertWallet(poor, 10_000, 0);
    }

    @Test
    void unknownListingIs404() throws Exception {
        Snapshot before = snapshot();

        bid(alice, Long.MAX_VALUE, START)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasons[0]").value("No listing with id " + Long.MAX_VALUE));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void fixedListingIs400() throws Exception {
        long fixedId = Fixtures.fixedListing(listingService, seller.getId(), 50_000);
        Snapshot before = snapshot();

        bid(alice, fixedId, 50_000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("Listing " + fixedId + " is a fixed-price listing; use buy-now"));

        assertThat(snapshot()).isEqualTo(before);
        Listing fixed = listingRepository.findById(fixedId).orElseThrow();
        assertThat(fixed.getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(fixed.getHighBidderId()).isNull();
        assertThat(fixed.getBidCount()).isZero();
    }

    @Test
    void inactiveListingIs409() throws Exception {
        listingService.reject(auctionId, "Counterfeit");
        Snapshot before = snapshot();

        bid(alice, auctionId, START)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]")
                        .value("Listing " + auctionId + " is REJECTED and is not accepting bids"));

        assertThat(snapshot()).isEqualTo(before);
        assertThat(listing().getStatus()).isEqualTo(ListingStatus.REJECTED);
    }

    @Test
    void databaseRejectsASecondBidForTheSameAmountOnTheSameListing() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        Snapshot before = snapshot();

        // Bypasses BidService (and its lock) on purpose: the unique constraint is the backstop if the lock ever fails.
        assertThatThrownBy(() -> bidRepository.saveAndFlush(new Bid(auctionId, bob.getId(), START, clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(bidRepository.countByListingId(auctionId)).isEqualTo(1);
    }

    @Test
    void endedAuctionIs409() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        Snapshot before = snapshot();
        clock.advance(Duration.ofDays(2));

        bid(bob, auctionId, 30_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Auction has ended"))
                .andExpect(jsonPath("$.reasons[0]").value("This auction ended at " + listing().getEndsAt()));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void auctionJustPastItsEndIsClosed() throws Exception {
        Listing listing = listing();
        clock.advance(Duration.between(clock.instant(), listing.getEndsAt()).plusMillis(1));

        bid(alice, auctionId, START).andExpect(status().isConflict());
    }

    @Test
    void sellerCannotBidOnOwnListing() throws Exception {
        walletService.deposit(seller.getId(), 100_000);
        Snapshot before = snapshot();

        bid(seller, auctionId, START)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("You cannot bid on your own listing"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void sellerCanBidOnSomeoneElsesAuction() throws Exception {
        User otherSeller = Fixtures.fundedUser(userService, walletService, Role.SELLER, 100_000);

        bid(otherSeller, auctionId, START).andExpect(status().isCreated());
    }

    @Test
    void adminCannotBidAndAnonymousIsUnauthorized() throws Exception {
        String admin = TestAuth.bearer(mvc, objectMapper, TestAuth.ADMIN);
        Snapshot before = snapshot();
        mvc.perform(post("/listings/{id}/bids", auctionId).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 20000}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/listings/{id}/bids", auctionId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 20000}"))
                .andExpect(status().isUnauthorized());

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void amountIsRequiredAndPositive() throws Exception {
        String auth = TestAuth.bearer(mvc, objectMapper, alice.getEmail());
        mvc.perform(post("/listings/{id}/bids", auctionId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("amount is required"));
        mvc.perform(post("/listings/{id}/bids", auctionId).header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": -1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anySequenceOfBidsConservesMoneyAndKeepsTheInvariant() throws Exception {
        User carol = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        long otherAuction = Fixtures.auctionListing(listingService, seller.getId());
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        record Step(User bidder, long listing, long amount) {
        }
        List<Step> steps = List.of(
                new Step(alice, auctionId, 20_000),
                new Step(bob, auctionId, 21_000),
                new Step(alice, otherAuction, 20_000),
                new Step(alice, auctionId, 30_000),
                new Step(alice, auctionId, 35_000),
                new Step(carol, otherAuction, 22_000),
                new Step(carol, auctionId, 40_000),
                new Step(bob, otherAuction, 50_000));
        for (Step step : steps) {
            bid(step.bidder(), step.listing(), step.amount()).andExpect(status().isCreated());
            assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
            assertInvariants();
        }

        assertWallet(alice, 100_000, 0);
        assertWallet(bob, 50_000, 50_000);
        assertWallet(carol, 60_000, 40_000);
    }

    @Test
    void bidHistoryIsPublicNewestFirstAndMasked() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());
        bid(bob, auctionId, 25_000).andExpect(status().isCreated());

        String body = mvc.perform(get("/listings/{id}/bids", auctionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].amountPaise").value(25_000))
                .andExpect(jsonPath("$[0].bidder").value(BidResponse.maskEmail(bob.getEmail())))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].amountPaise").value(START))
                .andExpect(jsonPath("$[0].bidderId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(alice.getEmail()).doesNotContain(bob.getEmail());
    }

    @Test
    void bidHistoryOfHiddenListingIs404() throws Exception {
        listingService.reject(auctionId, "Counterfeit");

        mvc.perform(get("/listings/{id}/bids", auctionId)).andExpect(status().isNotFound());
    }

    @Test
    void listingShowsBidStateAndWhetherTheViewerLeads() throws Exception {
        bid(alice, auctionId, START).andExpect(status().isCreated());

        mvc.perform(get("/listings/{id}", auctionId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(mvc, objectMapper, alice.getEmail())))
                .andExpect(jsonPath("$.currentHighBid").value(START))
                .andExpect(jsonPath("$.bidCount").value(1))
                .andExpect(jsonPath("$.endsAt").isNotEmpty())
                .andExpect(jsonPath("$.viewerIsHighBidder").value(true));
        mvc.perform(get("/listings/{id}", auctionId).header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(mvc, objectMapper, bob.getEmail())))
                .andExpect(jsonPath("$.viewerIsHighBidder").value(false));
        mvc.perform(get("/listings/{id}", auctionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidCount").value(1))
                .andExpect(jsonPath("$.viewerIsHighBidder").isEmpty());
    }

    /** Everything a rejected bid must leave untouched. */
    private record Snapshot(long aliceAvailable, long aliceHeld, long bobAvailable, long bobHeld, long ledgerRows,
                            long bids, Long currentHighBid, Long highBidderId, int bidCount) {
    }

    private Snapshot snapshot() {
        Wallet a = wallet(alice);
        Wallet b = wallet(bob);
        Listing listing = listing();
        return new Snapshot(a.getAvailableBalance(), a.getHeldBalance(), b.getAvailableBalance(), b.getHeldBalance(),
                transactionRepository.count(), bidRepository.count(), listing.getCurrentHighBid(),
                listing.getHighBidderId(), listing.getBidCount());
    }

    private ResultActions bid(User bidder, long listingId, long amount) throws Exception {
        return mvc.perform(post("/listings/{id}/bids", listingId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(mvc, objectMapper, bidder.getEmail()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("amount", amount).toString()));
    }

    private void assertInvariants() {
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
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
