package com.bidbuygo.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletTransactionRepository;
import com.bidbuygo.wallet.WalletTransactionType;
import com.bidbuygo.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class DevDataSeederTest {

    @Autowired DevDataSeeder seeder;
    @Autowired UserRepository userRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WalletTransactionRepository transactionRepository;
    @Autowired ListingRepository listingRepository;

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
    void seedsListingsFromVerifiedSeller() {
        User seller = userRepository.findByEmail(DevDataSeeder.VERIFIED_SELLER).orElseThrow();
        List<Listing> seeded = listingRepository.findAll().stream()
                .filter(l -> l.getSellerId().equals(seller.getId()))
                .filter(l -> SEEDED_TITLES.contains(l.getTitle()))
                .toList();

        assertThat(seeded).hasSize(4);
        assertThat(seeded).filteredOn(l -> l.getType() == ListingType.FIXED).hasSize(2);
        assertThat(seeded).filteredOn(l -> l.getType() == ListingType.AUCTION).hasSize(2)
                .allSatisfy(l -> assertThat(l.getEndsAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS)));
    }

    @Test
    void rerunningIsIdempotent() {
        long users = userRepository.count();
        long entries = transactionRepository.count();
        long listings = listingRepository.count();

        seeder.run(null);

        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(transactionRepository.count()).isEqualTo(entries);
        assertThat(listingRepository.count()).isEqualTo(listings);
    }

    private static final List<String> SEEDED_TITLES =
            DevDataSeeder.listingDrafts(Instant.now()).stream().map(d -> d.title()).toList();

    private User assertUser(String email, Role role, boolean verified, int completedOrders) {
        User user = userRepository.findByEmail(email).orElseThrow();
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.isVerified()).isEqualTo(verified);
        assertThat(user.getCompletedOrders()).isEqualTo(completedOrders);
        assertThat(walletRepository.findByUserId(user.getId())).isPresent();
        return user;
    }
}
