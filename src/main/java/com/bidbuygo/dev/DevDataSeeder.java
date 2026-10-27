package com.bidbuygo.dev;

import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds demo accounts in the dev profile. Idempotent: an account that already exists is left untouched,
 * so restarts against a persistent database don't duplicate users or re-fund wallets.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
class DevDataSeeder implements ApplicationRunner {

    static final String DEV_PASSWORD = "password123";
    static final long BUYER_STARTING_BALANCE = 50_000L;

    private final UserService userService;
    private final WalletService walletService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed("admin@bidbuygo.dev", Role.ADMIN, false, 0, 0);
        seed("seller.verified@bidbuygo.dev", Role.SELLER, true, 10, 0);
        seed("seller.new@bidbuygo.dev", Role.SELLER, false, 0, 0);
        seed("buyer1@bidbuygo.dev", Role.BUYER, false, 0, BUYER_STARTING_BALANCE);
        seed("buyer2@bidbuygo.dev", Role.BUYER, false, 0, BUYER_STARTING_BALANCE);
    }

    private void seed(String email, Role role, boolean verified, int completedOrders, long startingBalance) {
        if (userService.findByEmail(email).isPresent()) {
            return;
        }
        User user = userService.create(email, DEV_PASSWORD, role);
        user.setVerified(verified);
        user.setCompletedOrders(completedOrders);
        if (startingBalance > 0) {
            walletService.deposit(user.getId(), startingBalance);
        }
        log.info("Seeded dev user {} ({})", email, role);
    }
}
