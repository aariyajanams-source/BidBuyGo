package com.bidbuygo.auction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;

import com.bidbuygo.bid.BidRepository;
import com.bidbuygo.listing.CloseReason;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.IntegrationTest;
import com.bidbuygo.support.MutableClock;
import com.bidbuygo.support.TestAuth;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.WalletService;
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

@IntegrationTest
class AntiSnipingTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MutableClock clock;
    @Autowired AuctionProperties auctionProperties;
    @Autowired AuctionSettlementService settlementService;
    @Autowired BidRepository bidRepository;
    @Autowired UserService userService;
    @Autowired UserRepository userRepository;
    @Autowired WalletService walletService;
    @Autowired ListingService listingService;
    @Autowired ListingRepository listingRepository;

    private User alice;
    private User bob;
    private long auctionId;

    @BeforeEach
    void setUp() {
        User seller = Fixtures.trustedSeller(userService, userRepository);
        alice = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        bob = Fixtures.fundedUser(userService, walletService, Role.BUYER, 100_000);
        auctionId = Fixtures.auctionListing(listingService, seller.getId());
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    @Test
    void defaultsAreThirtySecondWindowAndThirtySecondExtension() {
        assertThat(auctionProperties.snipeWindow()).isEqualTo(Duration.ofSeconds(30));
        assertThat(auctionProperties.extension()).isEqualTo(Duration.ofSeconds(30));
        assertThat(auctionProperties.closePollMs()).isEqualTo(5_000);
    }

    @Test
    void bidInsideTheWindowExtendsTheAuction() throws Exception {
        Instant originalEnd = endsAt();
        moveToRemaining(Duration.ofSeconds(20));

        JsonNode response = bid(alice, 20_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.extended").value(true))
                .andReturn().getResponse().getContentAsString().transform(this::json);

        Instant expected = originalEnd.plus(auctionProperties.extension());
        assertThat(Instant.parse(response.get("endsAt").asText())).isEqualTo(expected);
        assertThat(endsAt()).isEqualTo(expected);
    }

    @Test
    void bidOutsideTheWindowDoesNotExtend() throws Exception {
        Instant originalEnd = endsAt();
        moveToRemaining(Duration.ofSeconds(60));

        JsonNode response = bid(alice, 20_000).andExpect(status().isCreated())
                .andExpect(jsonPath("$.extended").value(false))
                .andReturn().getResponse().getContentAsString().transform(this::json);

        assertThat(Instant.parse(response.get("endsAt").asText())).isEqualTo(originalEnd);
        assertThat(endsAt()).isEqualTo(originalEnd);
    }

    @Test
    void eachLateBidExtendsAgain() throws Exception {
        Instant originalEnd = endsAt();
        Duration extension = auctionProperties.extension();

        moveToRemaining(Duration.ofSeconds(20));
        bid(alice, 20_000).andExpect(jsonPath("$.extended").value(true));
        assertThat(endsAt()).isEqualTo(originalEnd.plus(extension));

        moveToRemaining(Duration.ofSeconds(10));
        bid(bob, 21_000).andExpect(jsonPath("$.extended").value(true));
        assertThat(endsAt()).isEqualTo(originalEnd.plus(extension).plus(extension));
    }

    @Test
    void extendedAuctionIsNotClosedAtItsOriginalEnd() {
        Instant originalEnd = endsAt();
        moveToRemaining(Duration.ofSeconds(5));
        placeExtendingBid(alice, 20_000);

        clock.advance(Duration.between(clock.instant(), originalEnd).plusSeconds(1));

        assertThat(settlementService.closeIfDue(auctionId)).isEmpty();
        assertThat(listingRepository.findById(auctionId).orElseThrow().getStatus()).isEqualTo(ListingStatus.ACTIVE);
    }

    @Test
    void bidAfterTheEndButBeforeTheJobRunsIsRejected() throws Exception {
        bid(alice, 20_000).andExpect(status().isCreated());
        long bidsBefore = bidRepository.countByListingId(auctionId);
        clock.advance(Duration.between(clock.instant(), endsAt()).plusSeconds(1));

        bid(bob, 30_000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Auction has ended"));

        assertThat(bidRepository.countByListingId(auctionId)).isEqualTo(bidsBefore);
        assertThat(listingRepository.findById(auctionId).orElseThrow().getHighBidderId()).isEqualTo(alice.getId());
        // The job then settles it with the last accepted bid (below reserve here).
        assertThat(settlementService.closeIfDue(auctionId)).contains(CloseReason.RESERVE_NOT_MET);
    }

    private void placeExtendingBid(User bidder, long amount) {
        try {
            bid(bidder, amount).andExpect(status().isCreated()).andExpect(jsonPath("$.extended").value(true));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void moveToRemaining(Duration remaining) {
        clock.advance(Duration.between(clock.instant(), endsAt()).minus(remaining));
    }

    private Instant endsAt() {
        return listingRepository.findById(auctionId).orElseThrow().getEndsAt();
    }

    private ResultActions bid(User bidder, long amount) throws Exception {
        return mvc.perform(post("/listings/{id}/bids", auctionId)
                .header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(mvc, objectMapper, bidder.getEmail()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("amount", amount).toString()));
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
