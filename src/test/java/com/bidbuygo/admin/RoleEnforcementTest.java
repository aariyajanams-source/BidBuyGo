package com.bidbuygo.admin;

import static com.bidbuygo.support.TestAuth.ADMIN;
import static com.bidbuygo.support.TestAuth.BUYER;
import static com.bidbuygo.support.TestAuth.VERIFIED_SELLER;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bidbuygo.support.TestAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RoleEnforcementTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {BUYER, VERIFIED_SELLER})
    void nonAdminsAreForbiddenFromAdminEndpoints(String email) throws Exception {
        String auth = TestAuth.bearer(mvc, objectMapper, email);

        mvc.perform(get("/admin/policy").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Forbidden"));
        mvc.perform(put("/admin/policy").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/ping").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin/listings").header(HttpHeaders.AUTHORIZATION, auth))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanPing() throws Exception {
        mvc.perform(get("/admin/ping").header(HttpHeaders.AUTHORIZATION, TestAuth.bearer(mvc, objectMapper, ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void buyerCannotCreateListings() throws Exception {
        String auth = TestAuth.bearer(mvc, objectMapper, BUYER);

        mvc.perform(post("/listings").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/listings/check").header(HttpHeaders.AUTHORIZATION, auth)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCanBrowseButNotCreate() throws Exception {
        mvc.perform(post("/listings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/listings/check").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/listings")).andExpect(status().isOk());
        mvc.perform(get("/admin/ping")).andExpect(status().isUnauthorized());
    }
}
