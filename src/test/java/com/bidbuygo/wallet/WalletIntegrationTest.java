package com.bidbuygo.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class WalletIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserService userService;
    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;

    private User user;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        String email = "wallet-" + UUID.randomUUID() + "@test.dev";
        user = userService.create(email, "password123", Role.BUYER);
        String body = mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(body).get("accessToken").asText();
    }

    @Test
    void newWalletIsEmpty() throws Exception {
        mvc.perform(get("/wallet").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(0))
                .andExpect(jsonPath("$.heldBalance").value(0));
    }

    @Test
    void topupIncreasesAvailableAndWritesDepositEntry() throws Exception {
        topup("{\"amount\": 25000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(25000))
                .andExpect(jsonPath("$.heldBalance").value(0));

        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();
        List<WalletTransaction> entries = transactionRepository.findByWalletIdOrderByIdAsc(wallet.getId());
        assertThat(entries).singleElement().satisfies(entry -> {
            assertThat(entry.getType()).isEqualTo(WalletTransactionType.DEPOSIT);
            assertThat(entry.getAmount()).isEqualTo(25_000L);
            assertThat(entry.getCreatedAt()).isNotNull();
        });
    }

    @Test
    void topupAtCapIsAccepted() throws Exception {
        topup("{\"amount\": 10000000}").andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"amount\": 0}", "{\"amount\": -5}", "{\"amount\": 10000001}", "{}"})
    void topupRejectsInvalidAmounts(String body) throws Exception {
        topup(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasons[0]").exists());
        assertThat(walletRepository.findByUserId(user.getId()).orElseThrow().getAvailableBalance()).isZero();
    }

    @Test
    void topupRejectsFractionalAmounts() throws Exception {
        topup("{\"amount\": 10.5}").andExpect(status().isBadRequest());
    }

    @Test
    void walletRequiresAuthentication() throws Exception {
        mvc.perform(get("/wallet")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentDepositsAreNotLost() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<WalletResponse>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> walletService.deposit(user.getId(), 100));
            }
            for (Future<WalletResponse> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }

        Wallet wallet = walletRepository.findByUserId(user.getId()).orElseThrow();
        assertThat(wallet.getAvailableBalance()).isEqualTo(threads * 100L);
        assertThat(transactionRepository.findByWalletIdOrderByIdAsc(wallet.getId())).hasSize(threads);
    }

    private org.springframework.test.web.servlet.ResultActions topup(String body) throws Exception {
        return mvc.perform(post("/wallet/topup").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
