package com.bidbuygo.listing;

import static com.bidbuygo.support.TestAuth.ADMIN;
import static com.bidbuygo.support.TestAuth.NEW_SELLER;
import static com.bidbuygo.support.TestAuth.VERIFIED_SELLER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.bidbuygo.bid.BidService;
import com.bidbuygo.support.Fixtures;
import com.bidbuygo.support.TestAuth;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ListingIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ListingRepository listingRepository;
    @Autowired BidService bidService;
    @Autowired UserService userService;
    @Autowired WalletService walletService;

    private String verifiedSeller;
    private String newSeller;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        verifiedSeller = TestAuth.bearer(mvc, objectMapper, VERIFIED_SELLER);
        newSeller = TestAuth.bearer(mvc, objectMapper, NEW_SELLER);
        admin = TestAuth.bearer(mvc, objectMapper, ADMIN);
    }

    @Test
    void fixedListingFromUnverifiedSellerSucceeds() throws Exception {
        create(newSeller, fixedDraft())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("FIXED"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.fixedPrice").value(150_000))
                .andExpect(jsonPath("$.category").value("pre-owned"))
                .andExpect(jsonPath("$.startPrice").doesNotExist());
    }

    @Test
    void auctionFromUnverifiedSellerIs422WithReasons() throws Exception {
        long before = listingRepository.count();

        create(newSeller, auctionDraft())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Listing is not eligible for auction"))
                .andExpect(jsonPath("$.reasons", hasItems(
                        "Seller must be verified to auction",
                        "Seller needs at least 5 completed orders to auction; has 0")));

        assertThat(listingRepository.count()).isEqualTo(before);
    }

    @Test
    void auctionFromVerifiedSellerSucceeds() throws Exception {
        create(verifiedSeller, auctionDraft())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("AUCTION"))
                .andExpect(jsonPath("$.startPrice").value(20_000))
                .andExpect(jsonPath("$.reservePrice").value(50_000))
                .andExpect(jsonPath("$.currentHighBid").doesNotExist())
                .andExpect(jsonPath("$.fixedPrice").doesNotExist());
    }

    @Test
    void twoMinuteAuctionIsAllowed() throws Exception {
        ObjectNode draft = auctionDraft().put("endsAt", Instant.now().plus(2, ChronoUnit.MINUTES).toString());
        create(verifiedSeller, draft).andExpect(status().isCreated());
    }

    @Test
    void invalidDraftIs400WithEveryReason() throws Exception {
        ObjectNode draft = objectMapper.createObjectNode()
                .put("type", "AUCTION")
                .put("startPrice", 50_000)
                .put("reservePrice", 10_000)
                .put("endsAt", Instant.now().plus(15, ChronoUnit.DAYS).toString());

        create(verifiedSeller, draft)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid listing"))
                .andExpect(jsonPath("$.reasons", hasItems(
                        "title is required",
                        "category is required",
                        "conditionGrade is required",
                        "reservePrice must be at least startPrice",
                        "endsAt must be at most 14 days from now")));
    }

    @Test
    void fixedListingNeedsPositivePrice() throws Exception {
        create(newSeller, fixedDraft().put("fixedPrice", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons", hasItem("fixedPrice must be greater than 0 paise for FIXED listings")));
    }

    @Test
    void auctionMustEndInTheFuture() throws Exception {
        create(verifiedSeller, auctionDraft().put("endsAt", Instant.now().minus(1, ChronoUnit.MINUTES).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons", hasItem("endsAt must be in the future")));
    }

    @Test
    void checkReturnsEligibilityAndSavesNothing() throws Exception {
        long before = listingRepository.count();

        check(newSeller, auctionDraft())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasons", hasItem("Seller must be verified to auction")));
        check(verifiedSeller, auctionDraft())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reasons").isEmpty());
        check(newSeller, fixedDraft())
                .andExpect(jsonPath("$.eligible").value(true));

        assertThat(listingRepository.count()).isEqualTo(before);
    }

    @Test
    void publicListFiltersByTypeAndCategory() throws Exception {
        mvc.perform(get("/listings").param("type", "AUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].type", everyItem(is("AUCTION"))))
                .andExpect(jsonPath("$[*].status", everyItem(is("ACTIVE"))));

        mvc.perform(get("/listings").param("category", "HANDMADE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].category", everyItem(is("handmade"))))
                .andExpect(jsonPath("$[*].title", hasItem("Handwoven jute tote bag")));
    }

    @Test
    void unknownTypeFilterIs400() throws Exception {
        mvc.perform(get("/listings").param("type", "DUTCH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("type must be one of: FIXED, AUCTION"));
    }

    @Test
    void rejectedListingIsHiddenFromPublicButVisibleToOwnerAndAdmin() throws Exception {
        long id = createdId(newSeller, fixedDraft());

        reject(id, "Counterfeit item")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Counterfeit item"));

        mvc.perform(get("/listings/{id}", id)).andExpect(status().isNotFound());
        mvc.perform(get("/listings/{id}", id).header(HttpHeaders.AUTHORIZATION, verifiedSeller))
                .andExpect(status().isNotFound());
        mvc.perform(get("/listings"))
                .andExpect(jsonPath("$[*].id", not(hasItem((int) id))));

        mvc.perform(get("/listings/{id}", id).header(HttpHeaders.AUTHORIZATION, newSeller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectionReason").value("Counterfeit item"));
        mvc.perform(get("/listings/{id}", id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mvc.perform(get("/admin/listings").param("status", "REJECTED").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$[*].id", hasItem((int) id)))
                .andExpect(jsonPath("$[*].status", everyItem(is("REJECTED"))));
    }

    @Test
    void activeListingIsPublic() throws Exception {
        long id = createdId(newSeller, fixedDraft());

        mvc.perform(get("/listings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void rejectingTwiceIs409() throws Exception {
        long id = createdId(newSeller, fixedDraft());
        reject(id, "Spam").andExpect(status().isOk());

        reject(id, "Spam again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]").value("Only ACTIVE listings can be rejected; this listing is REJECTED"));
    }

    @Test
    void auctionWithHighBidderCannotBeRejected() throws Exception {
        long id = createdId(verifiedSeller, auctionDraft());
        User bidder = Fixtures.fundedUser(userService, walletService, Role.BUYER, 50_000);
        bidService.placeBid(id, bidder.getId(), 25_000);

        reject(id, "Policy violation")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]")
                        .value("This auction already has bids; rejecting it would require refunding held bids"));
    }

    @Test
    void rejectRequiresReason() throws Exception {
        long id = createdId(newSeller, fixedDraft());

        mvc.perform(post("/admin/listings/{id}/reject", id).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("reason is required"));
    }

    @Test
    void rejectUnknownListingIs404() throws Exception {
        reject(Long.MAX_VALUE, "Nope").andExpect(status().isNotFound());
    }

    private ObjectNode fixedDraft() {
        ObjectNode draft = objectMapper.createObjectNode()
                .put("title", "Used denim jacket")
                .put("description", "Size M")
                .put("category", "Pre-Owned")
                .put("conditionGrade", "GOOD")
                .put("type", "FIXED")
                .put("fixedPrice", 150_000);
        draft.putArray("photoUrls").add("https://img.test/jacket.jpg");
        return draft;
    }

    private ObjectNode auctionDraft() {
        ObjectNode draft = objectMapper.createObjectNode()
                .put("title", "First-edition comic")
                .put("category", "collectibles")
                .put("conditionGrade", "LIKE_NEW")
                .put("type", "AUCTION")
                .put("startPrice", 20_000)
                .put("reservePrice", 50_000)
                .put("endsAt", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        draft.putArray("photoUrls").add("https://img.test/comic-1.jpg").add("https://img.test/comic-2.jpg");
        return draft;
    }

    private ResultActions create(String auth, ObjectNode draft) throws Exception {
        return mvc.perform(post("/listings").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(draft.toString()));
    }

    private ResultActions check(String auth, ObjectNode draft) throws Exception {
        return mvc.perform(post("/listings/check").header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON).content(draft.toString()));
    }

    private ResultActions reject(long id, String reason) throws Exception {
        return mvc.perform(post("/admin/listings/{id}/reject", id).header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode().put("reason", reason).toString()));
    }

    private long createdId(String auth, ObjectNode draft) throws Exception {
        String body = create(auth, draft).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }
}
