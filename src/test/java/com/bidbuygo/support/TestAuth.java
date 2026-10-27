package com.bidbuygo.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Logs in seeded dev accounts (or any account) and returns an Authorization header value. */
public final class TestAuth {

    public static final String DEV_PASSWORD = "password123";
    public static final String ADMIN = "admin@bidbuygo.dev";
    public static final String VERIFIED_SELLER = "seller.verified@bidbuygo.dev";
    public static final String NEW_SELLER = "seller.new@bidbuygo.dev";
    public static final String BUYER = "buyer1@bidbuygo.dev";

    private TestAuth() {
    }

    public static String bearer(MockMvc mvc, ObjectMapper objectMapper, String email) throws Exception {
        return bearer(mvc, objectMapper, email, DEV_PASSWORD);
    }

    public static String bearer(MockMvc mvc, ObjectMapper objectMapper, String email, String password) throws Exception {
        String body = objectMapper.createObjectNode().put("email", email).put("password", password).toString();
        String response = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + objectMapper.readTree(response).get("accessToken").asText();
    }
}
