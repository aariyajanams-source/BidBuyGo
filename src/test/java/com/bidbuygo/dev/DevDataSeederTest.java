package com.bidbuygo.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletTransactionRepository;
import com.bidbuygo.wallet.WalletTransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class DevDataSeederTest {

    @Autowired DevDataSeeder seeder;
    @Autowired UserRepository userRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;

    @Test
    void seedsExpectedAccounts() {
        assertUser("admin@bidbuygo.dev", Role.ADMIN, false, 0);
        assertUser("seller.verified@bidbuygo.dev", Role.SELLER, true, 10);
        assertUser("seller.new@bidbuygo.dev", Role.SELLER, false, 0);

        for (String email : new String[] {"buyer1@bidbuygo.dev", "buyer2@bidbuygo.dev"}) {
            User buyer = assertUser(email, Role.BUYER, false, 0);
            Wallet wallet = walletRepository.findByUserId(buyer.getId()).orElseThrow();
            assertThat(wallet.getAvailableBalance()).isEqualTo(DevDataSeeder.BUYER_STARTING_BALANCE);
            assertThat(wallet.getHeldBalance()).isZero();
            assertThat(transactionRepository.findByWalletIdOrderByIdAsc(wallet.getId()))
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.getType()).isEqualTo(WalletTransactionType.DEPOSIT);
                        assertThat(entry.getAmount()).isEqualTo(DevDataSeeder.BUYER_STARTING_BALANCE);
                    });
        }
    }

    @Test
    void rerunningIsIdempotent() {
        long users = userRepository.count();
        long entries = transactionRepository.count();

        seeder.run(null);

        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(transactionRepository.count()).isEqualTo(entries);
    }

    private User assertUser(String email, Role role, boolean verified, int completedOrders) {
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.isVerified()).isEqualTo(verified);
        assertThat(user.getCompletedOrders()).isEqualTo(completedOrders);
        assertThat(walletRepository.findByUserId(user.getId())).isPresent();
        return user;
    }
}
