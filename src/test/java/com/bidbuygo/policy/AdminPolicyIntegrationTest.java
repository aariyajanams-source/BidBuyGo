package com.bidbuygo.policy;

import static com.bidbuygo.support.TestAuth.ADMIN;
import static com.bidbuygo.support.TestAuth.NEW_SELLER;
import static com.bidbuygo.support.TestAuth.VERIFIED_SELLER;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.bidbuygo.support.TestAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class AdminPolicyIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private String admin;
    private String originalPolicy;

    @BeforeEach
    void setUp() throws Exception {
        admin = TestAuth.bearer(mvc, objectMapper, ADMIN);
        originalPolicy = mvc.perform(get("/admin/policy").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @AfterEach
    void restorePolicy() throws Exception {
        // The Spring context (and its database) is shared across test classes.
        putPolicy(originalPolicy).andExpect(status().isOk());
    }

    @Test
    void defaultsAreSeeded() throws Exception {
        mvc.perform(get("/admin/policy").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.allowedCategories", hasItems(
                        "collectibles", "pre-owned", "handmade", "limited-edition", "open-box")))
                .andExpect(jsonPath("$.allowedConditions", hasItems("NEW", "LIKE_NEW", "GOOD", "FAIR")))
                .andExpect(jsonPath("$.minSellerCompletedOrders").value(5))
                .andExpect(jsonPath("$.requireVerifiedSeller").value(true))
                .andExpect(jsonPath("$.minPhotos").value(2))
                .andExpect(jsonPath("$.minReservePaise").value(10_000))
                .andExpect(jsonPath("$.minIncrementPaise").value(1_000));
    }

    @Test
    void relaxingPolicyMakesNewSellerEligible() throws Exception {
        String newSeller = TestAuth.bearer(mvc, objectMapper, NEW_SELLER);
        check(newSeller).andExpect(jsonPath("$.eligible").value(false));

        ObjectNode relaxed = policy().put("requireVerifiedSeller", false).put("minSellerCompletedOrders", 0);
        putPolicy(relaxed.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireVerifiedSeller").value(false));

        check(newSeller)
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.reasons").isEmpty());
    }

    @Test
    void tighteningPolicyMakesVerifiedSellerIneligible() throws Exception {
        String verifiedSeller = TestAuth.bearer(mvc, objectMapper, VERIFIED_SELLER);
        check(verifiedSeller).andExpect(jsonPath("$.eligible").value(true));

        ObjectNode strict = policy();
        strict.putArray("allowedCategories").add("Open-Box");
        putPolicy(strict.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedCategories[0]").value("open-box"));

        check(verifiedSeller)
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.reasons[0]").value("Category 'collectibles' is not auctionable; allowed: open-box"));
    }

    @Test
    void invalidPolicyIs400WithEveryReason() throws Exception {
        ObjectNode invalid = policy()
                .put("minSellerCompletedOrders", -1)
                .put("minPhotos", -2)
                .put("minReservePaise", 0)
                .put("minIncrementPaise", 0);
        invalid.putArray("allowedCategories");
        invalid.putArray("allowedConditions");

        putPolicy(invalid.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons", hasItems(
                        "allowedCategories must contain at least one category",
                        "allowedConditions must contain at least one condition",
                        "minSellerCompletedOrders must not be negative",
                        "minPhotos must not be negative",
                        "minReservePaise must be greater than 0 paise",
                        "minIncrementPaise must be greater than 0 paise")));
    }

    @Test
    void blankCategoryIsRejected() throws Exception {
        ObjectNode invalid = policy();
        invalid.putArray("allowedCategories").add("collectibles").add(" ");

        putPolicy(invalid.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("allowedCategories[1] must not be blank"));
    }

    private ObjectNode policy() throws Exception {
        return (ObjectNode) objectMapper.readTree(originalPolicy);
    }

    private ResultActions putPolicy(String body) throws Exception {
        return mvc.perform(put("/admin/policy").header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions check(String auth) throws Exception {
        ObjectNode draft = objectMapper.createObjectNode()
                .put("title", "Signed vinyl")
                .put("category", "collectibles")
                .put("conditionGrade", "GOOD")
                .put("type", "AUCTION")
                .put("startPrice", 20_000)
                .put("reservePrice", 50_000)
                .put("endsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString());
        draft.putArray("photoUrls").add("https://img.test/1.jpg").add("https://img.test/2.jpg");
        return mvc.perform(post("/listings/check").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content(draft.toString()))
                .andExpect(status().isOk());
    }
}
