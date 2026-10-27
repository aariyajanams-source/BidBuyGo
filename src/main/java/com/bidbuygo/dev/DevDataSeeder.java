package com.bidbuygo.dev;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.bidbuygo.listing.ConditionGrade;
import com.bidbuygo.listing.ListingDraft;
import com.bidbuygo.listing.ListingService;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import com.bidbuygo.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds demo accounts and listings in the dev profile. Idempotent: an account or listing (by seller + title)
 * that already exists is left untouched, so restarts against a persistent database don't duplicate data
 * or re-fund wallets. Runs after the auction policy seeder, since seeded auctions must pass eligibility.
 */
@Slf4j
@Component
@Profile("dev")
@Order(10)
@RequiredArgsConstructor
class DevDataSeeder implements ApplicationRunner {

    static final String DEV_PASSWORD = "password123";
    static final long BUYER_STARTING_BALANCE = 50_000L;
    static final String VERIFIED_SELLER = "seller.verified@bidbuygo.dev";

    private final UserService userService;
    private final WalletService walletService;
    private final ListingService listingService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUser("admin@bidbuygo.dev", Role.ADMIN, false, 0, 0);
        seedUser(VERIFIED_SELLER, Role.SELLER, true, 10, 0);
        seedUser("seller.new@bidbuygo.dev", Role.SELLER, false, 0, 0);
        seedUser("buyer1@bidbuygo.dev", Role.BUYER, false, 0, BUYER_STARTING_BALANCE);
        seedUser("buyer2@bidbuygo.dev", Role.BUYER, false, 0, BUYER_STARTING_BALANCE);

        User seller = userService.findByEmail(VERIFIED_SELLER).orElseThrow();
        listingDrafts(Instant.now()).forEach(draft -> seedListing(seller, draft));
    }

    static List<ListingDraft> listingDrafts(Instant now) {
        Instant endsAt = now.plus(Duration.ofDays(7));
        return List.of(
                new ListingDraft("Vintage Polaroid SX-70 camera",
                        "Tested with a fresh film pack; leather skin intact.", "pre-owned", ConditionGrade.GOOD,
                        List.of(photo("polaroid-1")), ListingType.FIXED, 450_000L, null, null, null),
                new ListingDraft("Handwoven jute tote bag",
                        "Made by a weaving collective in West Bengal.", "handmade", ConditionGrade.NEW,
                        List.of(photo("jute-1")), ListingType.FIXED, 89_900L, null, null, null),
                new ListingDraft("Signed 2011 World Cup cricket ball",
                        "Comes with certificate of authenticity.", "collectibles", ConditionGrade.LIKE_NEW,
                        List.of(photo("ball-1"), photo("ball-2")), ListingType.AUCTION, null, 500_000L, 1_500_000L,
                        endsAt),
                new ListingDraft("Limited-edition art print (12/50)",
                        "Giclée print, numbered and signed by the artist.", "limited-edition", ConditionGrade.NEW,
                        List.of(photo("print-1"), photo("print-2")), ListingType.AUCTION, null, 200_000L, 600_000L,
                        endsAt));
    }

    private void seedUser(String email, Role role, boolean verified, int completedOrders, long startingBalance) {
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

    private void seedListing(User seller, ListingDraft draft) {
        if (listingService.existsForSeller(seller.getId(), draft.title())) {
            return;
        }
        listingService.create(seller.getId(), draft);
        log.info("Seeded dev listing '{}' ({})", draft.title(), draft.type());
    }

    private static String photo(String seed) {
        return "https://picsum.photos/seed/bidbuygo-" + seed + "/800/600";
    }
}
