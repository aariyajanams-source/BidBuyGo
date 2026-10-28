package com.bidbuygo.listing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.bidbuygo.auction.AuctionSettlementService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** reservePrice reaches only the listing's seller and admins; everyone else sees reserveMet. */
@IntegrationTest
class ReservePrivacyTest {

    // An odd amount so a leak is easy to spot anywhere in a response body.
    private static final long RESERVE = 73_457;
    private static final long START = 20_000;
    private static final List<String> RESERVE_TEXT = List.of("73457", "734.57");

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
    @Autowired AuctionSettlementService settlementService;

    private User owner;
    private String ownerToken;
    private String admin;
    private User buyer;
    private String buyerToken;
    private String otherSellerToken;
    private long auctionId;

    @BeforeEach
    void setUp() throws Exception {
        owner = Fixtures.trustedSeller(userService, userRepository);
        ownerToken = bearer(owner);
        admin = TestAuth.bearer(mvc, objectMapper, TestAuth.ADMIN);
        buyer = Fixtures.fundedUser(userService, walletService, Role.BUYER, 200_000);
        buyerToken = bearer(buyer);
        otherSellerToken = bearer(Fixtures.trustedSeller(userService, userRepository));

        String created = mvc.perform(post("/listings").header(HttpHeaders.AUTHORIZATION, ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(auctionDraft()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode createdJson = objectMapper.readTree(created);
        auctionId = createdJson.get("id").asLong();
        // POST /listings: the response goes to the owner, who sees their reserve.
        assertThat(createdJson.get("reservePrice").asLong()).isEqualTo(RESERVE);
        assertThat(createdJson.get("reserveMet").asBoolean()).isFalse();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void nonOwnersNeverSeeTheReserveOnAnyListingEndpoint() throws Exception {
        for (String token : new String[] {null, buyerToken, otherSellerToken}) {
            assertNoReserve(body(get("/listings/{id}", auctionId), token));
            assertNoReserve(listedItem(body(get("/listings"), token)));
            assertNoReserve(listedItem(body(get("/listings").param("type", "AUCTION"), token)));
        }
        // The admin endpoints are closed to everyone else, so they can't leak it either.
        mvc.perform(get("/admin/listings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/listings").header(HttpHeaders.AUTHORIZATION, buyerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/listings").header(HttpHeaders.AUTHORIZATION, otherSellerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerAndAdminSeeTheReserveOnEveryListingEndpoint() throws Exception {
        for (String token : new String[] {ownerToken, admin}) {
            assertReserve(objectMapper.readTree(body(get("/listings/{id}", auctionId), token)));
            assertReserve(listedItem(body(get("/listings"), token)));
        }
        assertReserve(listedItem(body(get("/admin/listings"), admin)));
        assertReserve(listedItem(body(get("/admin/listings").param("status", "ACTIVE"), admin)));
    }

    @Test
    void adminRejectResponseIncludesTheReserve() throws Exception {
        String rejected = mvc.perform(post("/admin/listings/{id}/reject", auctionId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Counterfeit\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertReserve(objectMapper.readTree(rejected));
    }

    @Test
    void reserveMetTracksTheLeadingBid() throws Exception {
        assertReserveMet(false); // no bids

        // RESERVE - INCREMENT, so the next allowed raise lands exactly on the reserve.
        bid(RESERVE - 1_000).andExpect(status().isCreated());
        assertReserveMet(false); // below the reserve

        bid(RESERVE).andExpect(status().isCreated());
        assertReserveMet(true); // exactly the reserve

        clock.advance(Duration.ofDays(2));
        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.SOLD);
        assertReserveMet(true); // stays true on the sold listing

        Fixtures.assertLedgerReconstructsEveryWallet(walletRepository, transactionRepository);
        Fixtures.assertAuctionHoldsMatchHighBids(listingRepository, walletRepository, transactionRepository);
    }

    @Test
    void reserveMetIsFalseWhenTheAuctionClosesBelowTheReserve() throws Exception {
        bid(START).andExpect(status().isCreated());
        clock.advance(Duration.ofDays(2));
        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.RESERVE_NOT_MET);

        JsonNode json = objectMapper.readTree(body(get("/listings/{id}", auctionId), null));
        assertThat(json.get("reserveMet").asBoolean()).isFalse();
        assertNoReserve(json.toString());
    }

    @Test
    void reserveMetIsNullForFixedListings() throws Exception {
        long fixedId = Fixtures.fixedListing(listingService, owner.getId(), 50_000);

        JsonNode json = objectMapper.readTree(body(get("/listings/{id}", fixedId), ownerToken));
        assertThat(json.get("reserveMet").isNull()).isTrue();
        assertThat(json.has("reservePrice")).isFalse();
    }

    @Test
    void bidRejectionsAndBidHistoryDoNotRevealTheReserve() throws Exception {
        User poorBuyer = Fixtures.fundedUser(userService, walletService, Role.BUYER, 1_000);

        String tooLow = bid(START - 1).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String broke = mvc.perform(bidRequest(START).header(HttpHeaders.AUTHORIZATION, bearer(poorBuyer)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        String placed = bid(START).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String raisedTooLittle = bid(START + 1).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        String ownBid = mvc.perform(bidRequest(RESERVE).header(HttpHeaders.AUTHORIZATION, ownerToken))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        for (String body : List.of(tooLow, broke, placed, raisedTooLittle, ownBid)) {
            assertNoReserve(body);
        }
        for (String token : new String[] {null, buyerToken, otherSellerToken}) {
            assertNoReserve(body(get("/listings/{id}/bids", auctionId), token));
        }

        clock.advance(Duration.ofDays(2));
        String ended = bid(RESERVE).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertNoReserve(ended);
    }

    private void assertReserveMet(boolean expected) throws Exception {
        for (String token : new String[] {null, buyerToken, otherSellerToken, ownerToken, admin}) {
            JsonNode json = objectMapper.readTree(body(get("/listings/{id}", auctionId), token));
            assertThat(json.get("reserveMet").asBoolean()).as("reserveMet").isEqualTo(expected);
        }
    }

    private void assertNoReserve(String body) throws Exception {
        assertNoReserve(objectMapper.readTree(body));
        assertThat(body).doesNotContain(RESERVE_TEXT);
    }

    private void assertNoReserve(JsonNode json) {
        assertThat(json.toString()).doesNotContain("reservePrice").doesNotContain(RESERVE_TEXT);
    }

    private void assertReserve(JsonNode json) {
        assertThat(json.get("reservePrice").asLong()).isEqualTo(RESERVE);
        assertThat(json.get("reserveMet").isBoolean()).isTrue();
    }

    /** The test auction's entry in a list response. */
    private JsonNode listedItem(String body) throws Exception {
        for (JsonNode item : objectMapper.readTree(body)) {
            if (item.get("id").asLong() == auctionId) {
                return item;
            }
        }
        throw new AssertionError("listing " + auctionId + " missing from " + body);
    }

    private String body(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, token);
        }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions bid(long amount) throws Exception {
        return mvc.perform(bidRequest(amount).header(HttpHeaders.AUTHORIZATION, buyerToken));
    }

    private MockHttpServletRequestBuilder bidRequest(long amount) {
        return post("/listings/{id}/bids", auctionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("amount", amount).toString());
    }

    private String auctionDraft() throws Exception {
        return objectMapper.writeValueAsString(new ListingDraft("Reserve privacy " + UUID.randomUUID(), null,
                "collectibles", ConditionGrade.GOOD, List.of("https://img.test/1.jpg", "https://img.test/2.jpg"),
                ListingType.AUCTION, null, START, RESERVE, Instant.now().plus(1, ChronoUnit.DAYS)));
    }

    private String bearer(User user) throws Exception {
        return TestAuth.bearer(mvc, objectMapper, user.getEmail());
    }
}
