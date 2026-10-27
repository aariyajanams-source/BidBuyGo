package com.bidbuygo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bidbuygo.listing.ConditionGrade;
import com.bidbuygo.listing.ListingDraft;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.Wallet;
import com.bidbuygo.wallet.WalletRepository;
import com.bidbuygo.wallet.WalletService;
import com.bidbuygo.wallet.WalletTransaction;
import com.bidbuygo.wallet.WalletTransactionRepository;

/** Creates isolated users and listings (unique emails) and checks global money invariants. */
public final class Fixtures {

    private Fixtures() {
    }

    public static User user(UserService userService, Role role) {
        return userService.create(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.dev",
                TestAuth.DEV_PASSWORD, role);
    }

    public static User fundedUser(UserService userService, WalletService walletService, Role role, long paise) {
        User user = user(userService, role);
        walletService.deposit(user.getId(), paise);
        return user;
    }

    public static long fixedListing(ListingService listingService, Long sellerId, long price) {
        ListingDraft draft = new ListingDraft("Fixed item " + UUID.randomUUID(), null, "pre-owned",
                ConditionGrade.GOOD, List.of(), ListingType.FIXED, price, null, null, null);
        return listingService.create(sellerId, draft).id();
    }

    /** Needs a seller that passes the default auction policy (e.g. the seeded verified seller). */
    public static long auctionListing(ListingService listingService, Long sellerId) {
        ListingDraft draft = new ListingDraft("Auction item " + UUID.randomUUID(), null, "collectibles",
                ConditionGrade.GOOD, List.of("https://img.test/1.jpg", "https://img.test/2.jpg"), ListingType.AUCTION,
                null, 20_000L, 50_000L, Instant.now().plus(1, ChronoUnit.DAYS));
        return listingService.create(sellerId, draft).id();
    }

    /** Sum of available + held across every wallet. */
    public static long totalMoney(WalletRepository walletRepository) {
        return walletRepository.findAll().stream()
                .mapToLong(w -> w.getAvailableBalance() + w.getHeldBalance())
                .sum();
    }

    /** Replays each wallet's ledger rows from zero and checks they reproduce its current balances exactly. */
    public static void assertLedgerReconstructsEveryWallet(WalletRepository walletRepository,
                                                           WalletTransactionRepository transactionRepository) {
        Map<Long, List<WalletTransaction>> byWallet = transactionRepository.findAll().stream()
                .collect(Collectors.groupingBy(t -> t.getWallet().getId()));
        for (Wallet wallet : walletRepository.findAll()) {
            List<WalletTransaction> rows = byWallet.getOrDefault(wallet.getId(), List.of());
            long available = rows.stream().mapToLong(WalletTransaction::getAvailableDelta).sum();
            long held = rows.stream().mapToLong(WalletTransaction::getHeldDelta).sum();
            assertThat(available).as("available replayed for wallet %d", wallet.getId())
                    .isEqualTo(wallet.getAvailableBalance());
            assertThat(held).as("held replayed for wallet %d", wallet.getId())
                    .isEqualTo(wallet.getHeldBalance());
        }
    }
}
