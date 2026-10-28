package com.bidbuygo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.bidbuygo.user.UserRepository;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class AuthIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired WalletRepository walletRepository;

    @Test
    void registerCreatesUserAndWalletWithoutExposingHash() throws Exception {
        String email = uniqueEmail();
        register(email, "password123", "SELLER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("SELLER"))
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.completedOrders").value(0))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        var user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getPasswordHash()).isNotEqualTo("password123").startsWith("$2");
        assertThat(walletRepository.findByUserId(user.getId())).isPresent();
    }

    @Test
    void registerNormalizesEmail() throws Exception {
        String email = uniqueEmail();
        register(email.toUpperCase(), "password123", "BUYER")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void registerRejectsAdminRole() throws Exception {
        register(uniqueEmail(), "password123", "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Registration rejected"))
                .andExpect(jsonPath("$.reasons[0]").value("role ADMIN cannot be self-registered; choose BUYER or SELLER"));
    }

    @Test
    void registerRejectsUnknownRole() throws Exception {
        register(uniqueEmail(), "password123", "SUPERUSER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").value("role must be one of: BUYER, SELLER, ADMIN"));
    }

    @Test
    void registerListsEveryValidationFailure() throws Exception {
        register("not-an-email", "short", "BUYER")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.reasons", hasSize(2)))
                .andExpect(jsonPath("$.reasons", hasItem("email must be a valid email address")))
                .andExpect(jsonPath("$.reasons", hasItem("password must be between 8 and 72 characters")));
    }

    @Test
    void registerDuplicateEmailIsConflict() throws Exception {
        String email = uniqueEmail();
        register(email, "password123", "BUYER").andExpect(status().isCreated());
        register(email, "password123", "SELLER")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasons[0]").value("An account with email " + email + " already exists"));
    }

    @Test
    void loginReturnsTokenThatAuthenticatesMe() throws Exception {
        String email = uniqueEmail();
        register(email, "password123", "BUYER").andExpect(status().isCreated());

        String token = login(email, "password123");

        mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("BUYER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void loginWithWrongPasswordIsUnauthorized() throws Exception {
        String email = uniqueEmail();
        register(email, "password123", "BUYER").andExpect(status().isCreated());

        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reasons[0]").value("Email or password is incorrect"));
    }

    @Test
    void loginWithUnknownEmailIsUnauthorized() throws Exception {
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", uniqueEmail(), "password", "password123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Unauthorized"))
                .andExpect(jsonPath("$.reasons[0]").value("A valid bearer token is required"));
    }

    @Test
    void meWithTamperedTokenIsUnauthorized() throws Exception {
        String email = uniqueEmail();
        register(email, "password123", "BUYER").andExpect(status().isCreated());
        String token = login(email, "password123");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        mvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reasons[0]").value("Bearer token is invalid or expired"));
    }

    @Test
    void wrongHttpMethodKeepsItsStatus() throws Exception {
        mvc.perform(get("/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.reasons[0]").exists());
    }

    @Test
    void unknownPathForAuthenticatedUserIsNotFound() throws Exception {
        String email = uniqueEmail();
        register(email, "password123", "BUYER").andExpect(status().isCreated());
        mvc.perform(get("/no-such-endpoint").header(HttpHeaders.AUTHORIZATION, "Bearer " + login(email, "password123")))
                .andExpect(status().isNotFound());
    }

    private ResultActions register(String email, String password, String role) throws Exception {
        return mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password, "role", role)));
    }

    private String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.get("accessToken").asText();
    }

    private String json(String... keyValues) throws Exception {
        var node = objectMapper.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.put(keyValues[i], keyValues[i + 1]);
        }
        return objectMapper.writeValueAsString(node);
    }

    static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@test.dev";
    }
}
