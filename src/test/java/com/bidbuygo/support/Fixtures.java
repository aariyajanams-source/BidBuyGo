package com.bidbuygo.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.bidbuygo.listing.ConditionGrade;
import com.bidbuygo.listing.Listing;
import com.bidbuygo.listing.ListingDraft;
import com.bidbuygo.listing.ListingRepository;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingStatus;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserRepository;
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

    /** A fresh SELLER who passes the default auction policy (verified, 10 completed orders). */
    public static User trustedSeller(UserService userService, UserRepository userRepository) {
        User seller = user(userService, Role.SELLER);
        seller.setVerified(true);
        seller.setCompletedOrders(10);
        return userRepository.save(seller);
    }

    /** Start ₹200.00, reserve ₹500.00, ends in 1 day. Needs a seller that passes the default auction policy. */
    public static long auctionListing(ListingService listingService, Long sellerId) {
        return auctionListing(listingService, sellerId, Instant.now().plus(1, ChronoUnit.DAYS));
    }

    public static long auctionListing(ListingService listingService, Long sellerId, Instant endsAt) {
        ListingDraft draft = new ListingDraft("Auction item " + UUID.randomUUID(), null, "collectibles",
                ConditionGrade.GOOD, List.of("https://img.test/1.jpg", "https://img.test/2.jpg"), ListingType.AUCTION,
                null, 20_000L, 50_000L, endsAt);
        return listingService.create(sellerId, draft).id();
    }

    /** Net money currently held for a listing across all wallets (sum of heldDelta of rows tagged with it). */
    public static long heldForListing(WalletTransactionRepository transactionRepository, long listingId) {
        return transactionRepository.findAll().stream()
                .filter(t -> Long.valueOf(listingId).equals(t.getListingId()))
                .mapToLong(WalletTransaction::getHeldDelta)
                .sum();
    }

    /**
     * The auction hold invariant: for every ACTIVE auction, the net money held for it across all wallets is exactly
     * currentHighBid, and all of it sits in the high bidder's wallet.
     */
    public static void assertAuctionHoldsMatchHighBids(ListingRepository listingRepository,
                                                       WalletRepository walletRepository,
                                                       WalletTransactionRepository transactionRepository) {
        Map<Long, Long> walletIdByUser = walletRepository.findAll().stream()
                .collect(Collectors.toMap(Wallet::getUserId, Wallet::getId));
        Map<Long, Map<Long, Long>> heldByListingThenWallet = transactionRepository.findAll().stream()
                .filter(t -> t.getListingId() != null)
                .collect(Collectors.groupingBy(WalletTransaction::getListingId,
                        Collectors.groupingBy(t -> t.getWallet().getId(),
                                Collectors.summingLong(WalletTransaction::getHeldDelta))));

        for (Listing listing : listingRepository.findAll()) {
            if (listing.getType() != ListingType.AUCTION || listing.getStatus() != ListingStatus.ACTIVE) {
                continue;
            }
            Map<Long, Long> nonZeroHolds = heldByListingThenWallet.getOrDefault(listing.getId(), Map.of())
                    .entrySet().stream()
                    .filter(e -> e.getValue() != 0)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            Map<Long, Long> expected = listing.getHighBidderId() == null
                    ? Map.of()
                    : Map.of(walletIdByUser.get(listing.getHighBidderId()), listing.getCurrentHighBid());
            assertThat(nonZeroHolds).as("holds by wallet for auction %d", listing.getId()).isEqualTo(expected);
        }
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
