package com.bidbuygo.bid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import com.bidbuygo.auction.AuctionSettlementService;
import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.policy.AuctionPolicyRequest;
import com.bidbuygo.policy.AuctionPolicyResponse;
import com.bidbuygo.policy.AuctionPolicyService;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.IntegrationTest;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.support.TestAuth;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransactionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** minNextBid on listing responses is exactly the lowest amount placeBid accepts. */
@IntegrationTest
class MinNextBidTest {

    // Fixtures.auctionListing: startPrice ₹200.00, ends in 1 day. Default min increment is ₹10.00.
    private static final long START = 20_000;
    private static final long INCREMENT = 1_000;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MutableClock clock;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;
    @Autowired AuctionPolicyService policyService;
    @Autowired AuctionSettlementService settlementService;

    private User seller;
    private User alice;
    private User bob;
    private long auctionId;
    private AuctionPolicyResponse originalPolicy;

    @BeforeEach
    void setUp() {
        originalPolicy = policyService.getResponse();
        seller = Fixtures.trustedSeller(userService, userRepository);
        alice = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        bob = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        auctionId = Fixtures.auctionListing(listingService, seller.getId());
    }

    @AfterEach
    void restore() {
        clock.reset();
        policyService.update(withIncrement(originalPolicy.minIncrementPaise()));
    }

    @Test
    void beforeAnyBidItIsTheStartPrice() throws Exception {
        assertThat(minNextBid()).isEqualTo(START);
        assertMinNextBidIsTheLowestAcceptedBid(alice);
    }

    @Test
    void afterABidItIsTheCurrentBidPlusTheIncrement() throws Exception {
        bid(alice, START).andExpect(status().isCreated());

        assertThat(minNextBid()).isEqualTo(START + INCREMENT);
        assertMinNextBidIsTheLowestAcceptedBid(bob);
        assertThat(minNextBid()).isEqualTo(START + 2 * INCREMENT);
        // The leader raising their own bid is held to the same minimum.
        assertMinNextBidIsTheLowestAcceptedBid(bob);
    }

    @Test
    void followsAPolicyIncrementChangeImmediately() throws Exception {
        bid(alice, START).andExpect(status().isCreated());

        policyService.update(withIncrement(2_500));

        assertThat(minNextBid()).isEqualTo(START + 2_500);
        assertMinNextBidIsTheLowestAcceptedBid(bob);
    }

    @Test
    void listEndpointsReportTheSameValue() throws Exception {
        bid(alice, START).andExpect(status().isCreated());
        String admin = TestAuth.bearer(mvc, objectMapper, TestAuth.ADMIN);
        // A jsonPath filter yields a list of JSON ints, so compare against an int.
        int expected = Math.toIntExact(START + INCREMENT);

        mvc.perform(get("/listings").param("type", "AUCTION"))
                .andExpect(jsonPath("$[?(@.id == %d)].minNextBid".formatted(auctionId)).value(expected));
        mvc.perform(get("/admin/listings").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[?(@.id == %d)].minNextBid".formatted(auctionId)).value(expected));
    }

    @Test
    void isNullForFixedListings() throws Exception {
        long fixedId = Fixtures.fixedListing(listingService, seller.getId(), 50_000);

        assertThat(listingJson(fixedId).get("minNextBid").isNull()).isTrue();
    }

    @Test
    void isNullOnceTheAuctionHasEnded() throws Exception {
        bid(alice, START).andExpect(status().isCreated());

        // Past endsAt but not settled yet: still ACTIVE, but placeBid would reject any amount.
        clock.advance(Duration.ofDays(2));
        assertThat(listingJson(auctionId).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(listingJson(auctionId).get("minNextBid").isNull()).isTrue();

        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.RESERVE_NOT_MET);
        assertThat(listingJson(auctionId).get("status").asText()).isEqualTo("CLOSED");
        assertThat(listingJson(auctionId).get("minNextBid").isNull()).isTrue();

        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
    }

    /** minNextBid - 1 is rejected as too low; minNextBid itself is accepted. */
    private void assertMinNextBidIsTheLowestAcceptedBid(User bidder) throws Exception {
        long min = minNextBid();
        bid(bidder, min - 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bid too low"));
        bid(bidder, min).andExpect(status().isCreated());

        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
    }

    private long minNextBid() throws Exception {
        JsonNode value = listingJson(auctionId).get("minNextBid");
        assertThat(value.isIntegralNumber()).as("minNextBid of an open auction").isTrue();
        return value.asLong();
    }

    private JsonNode listingJson(long id) throws Exception {
        String body = mvc.perform(get("/listings/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions bid(User bidder, long amount) throws Exception {
        String token = TestAuth.bearer(mvc, objectMapper, bidder.getEmail());
        return mvc.perform(post("/listings/{id}/bids", auctionId)
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("amount", amount).toString()));
    }

    private AuctionPolicyRequest withIncrement(long minIncrementPaise) {
        AuctionPolicyResponse p = originalPolicy;
        return new AuctionPolicyRequest(p.allowedCategories(), p.allowedConditions(), p.minSellerCompletedOrders(),
                p.requireVerifiedSeller(), p.minPhotos(), p.minReservePaise(), minIncrementPaise);
    }
}
