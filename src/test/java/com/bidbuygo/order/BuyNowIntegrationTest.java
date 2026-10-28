package com.bidbuygo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.support.Fixtures;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class BuyNowIntegrationTest {

    private static final long PRICE = 60_000;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;
    @Autowired OrderRepository orderRepository;

    private User seller;
    private User buyer;
    private long listingId;

    @BeforeEach
    void setUp() {
        seller = Fixtures.user(userService, Role.SELLER);
        buyer = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        listingId = Fixtures.fixedListing(listingService, seller.getId(), PRICE);
    }

    @Test
    void happyPathMovesMoneyRecordsOrderAndMarksListingSold() throws Exception {
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        buy(buyer, listingId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.listingId").value(listingId))
                .andExpect(jsonPath("$.buyerId").value(buyer.getId()))
                .andExpect(jsonPath("$.sellerId").value(seller.getId()))
                .andExpect(jsonPath("$.amountPaise").value(PRICE))
                .andExpect(jsonPath("$.type").value("FIXED_PURCHASE"))
                .andExpect(jsonPath("$.role").value("BUYER"));

        assertThat(wallet(buyer).getAvailableBalance()).isEqualTo(40_000);
        assertThat(wallet(buyer).getHeldBalance()).isZero();
        assertThat(wallet(seller).getAvailableBalance()).isEqualTo(PRICE);
        assertThat(ledger(buyer)).extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getHeldDelta, WalletTransaction::getListingId)
                .containsExactly(
                        tuple(WalletTransactionType.DEPOSIT, 100_000L, 0L, null),
                        tuple(WalletTransactionType.CAPTURE, -PRICE, 0L, listingId));
        assertThat(ledger(seller)).extracting(WalletTransaction::getType, WalletTransaction::getAvailableDelta,
                        WalletTransaction::getListingId)
                .containsExactly(tuple(WalletTransactionType.CREDIT, PRICE, listingId));

        Order order = orderRepository.findByListingId(listingId).orElseThrow();
        assertThat(order.getBuyerId()).isEqualTo(buyer.getId());
        assertThat(order.getAmountPaise()).isEqualTo(PRICE);
        assertThat(order.getType()).isEqualTo(OrderType.FIXED_PURCHASE);
        assertThat(listingRepository.findById(listingId).orElseThrow().getStatus()).isEqualTo(ListingStatus.SOLD);
        assertThat(userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders()).isEqualTo(1);

        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
    }

    @Test
    void ordersMineShowsEachSideWithItsRole() throws Exception {
        buy(buyer, listingId).andExpect(status().isCreated());

        mvc.perform(get("/orders/mine").header(HttpHeaders.AUTHORIZATION, auth(buyer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("BUYER"))
                .andExpect(jsonPath("$[0].listingTitle").isNotEmpty());
        mvc.perform(get("/orders/mine").header(HttpHeaders.AUTHORIZATION, auth(seller)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].role").value("SELLER"))
                .andExpect(jsonPath("$[0].listingId").value(listingId));
    }

    @Test
    void ordersMineIsNewestFirst() throws Exception {
        long second = Fixtures.fixedListing(listingService, seller.getId(), 1_000);
        buy(buyer, listingId).andExpect(status().isCreated());
        buy(buyer, second).andExpect(status().isCreated());

        mvc.perform(get("/orders/mine").header(HttpHeaders.AUTHORIZATION, auth(buyer)))
                .andExpect(jsonPath("$[0].listingId").value(second))
                .andExpect(jsonPath("$[1].listingId").value(listingId));
    }

    @Test
    void unknownListingIs404() throws Exception {
        buy(buyer, Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reasons[0]").value("No listing with id " + Long.MAX_VALUE));
    }

    @Test
    void alreadySoldIs409AndChangesNothing() throws Exception {
        User other = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        buy(other, listingId).andExpect(status().isCreated());
        Snapshot before = snapshot();

        buy(buyer, listingId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]").value("Listing " + listingId + " has already been sold"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void rejectedListingIs409() throws Exception {
        String admin = TestAuth.bearer(mvc, objectMapper, TestAuth.ADMIN);
        mvc.perform(post("/admin/listings/{id}/reject", listingId).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Spam\"}"))
                .andExpect(status().isOk());
        Snapshot before = snapshot();

        buy(buyer, listingId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]").value("Listing " + listingId + " is REJECTED and cannot be bought"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void auctionListingIs400() throws Exception {
        User verifiedSeller = userRepository.findByEmail(TestAuth.VERIFIED_SELLER).orElseThrow();
        long auctionId = Fixtures.auctionListing(listingService, verifiedSeller.getId());
        long moneyBefore = Fixtures.totalMoney(walletRepository);

        buy(buyer, auctionId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("Listing " + auctionId + " is an auction; use the bidding endpoint"));

        assertThat(listingRepository.findById(auctionId).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(Fixtures.totalMoney(walletRepository)).isEqualTo(moneyBefore);
    }

    @Test
    void sellerCannotBuyOwnListing() throws Exception {
        walletService.deposit(seller.getId(), 100_000);
        Snapshot before = snapshot();

        buy(seller, listingId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("You cannot buy your own listing"));

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void sellerCanBuyAnotherSellersListing() throws Exception {
        User otherSeller = Fixtures.fundedUser(userService, walletService, Role.SELLER, 100_000);

        buy(otherSeller, listingId).andExpect(status().isCreated());
    }

    @Test
    void insufficientFundsIs400WithPriceAndBalanceAndChangesNothing() throws Exception {
        User poorBuyer = Fixtures.fundedUser(userService, walletService, Role.BUYER, 10_000);
        Snapshot before = snapshot();
        long poorBefore = wallet(poorBuyer).getAvailableBalance();
        int poorRowsBefore = ledger(poorBuyer).size();

        buy(poorBuyer, listingId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient funds"))
                .andExpect(jsonPath("$.reasons[0]").value("The price is ₹600.00 but your available balance is ₹100.00"));

        assertThat(snapshot()).isEqualTo(before);
        assertThat(wallet(poorBuyer).getAvailableBalance()).isEqualTo(poorBefore);
        assertThat(ledger(poorBuyer)).hasSize(poorRowsBefore);
    }

    @Test
    void adminCannotBuy() throws Exception {
        String admin = TestAuth.bearer(mvc, objectMapper, TestAuth.ADMIN);

        mvc.perform(post("/listings/{id}/buy", listingId).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotBuy() throws Exception {
        mvc.perform(post("/listings/{id}/buy", listingId)).andExpect(status().isUnauthorized());
    }

    /** Everything a failed purchase must leave untouched. */
    private record Snapshot(long buyerAvailable, long buyerHeld, long sellerAvailable, long sellerHeld,
                            long ledgerRows, ListingStatus listingStatus, long orders, int sellerCompletedOrders) {
    }

    private Snapshot snapshot() {
        Wallet b = wallet(buyer);
        Wallet s = wallet(seller);
        return new Snapshot(b.getAvailableBalance(), b.getHeldBalance(), s.getAvailableBalance(), s.getHeldBalance(),
                transactionRepository.count(), listingRepository.findById(listingId).orElseThrow().getStatus(),
                orderRepository.count(), userRepository.findById(seller.getId()).orElseThrow().getCompletedOrders());
    }

    private ResultActions buy(User user, long id) throws Exception {
        return mvc.perform(post("/listings/{id}/buy", id).header(HttpHeaders.AUTHORIZATION, auth(user)));
    }

    private String auth(User user) throws Exception {
        return TestAuth.bearer(mvc, objectMapper, user.getEmail());
    }

    private Wallet wallet(User user) {
        return walletRepository.findByUserId(user.getId()).orElseThrow();
    }

    private List<WalletTransaction> ledger(User user) {
        return transactionRepository.findByWalletIdOrderByIdAsc(wallet(user).getId());
    }
}
