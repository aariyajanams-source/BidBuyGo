package com.bidbuygo.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.bidbuygo.listing.ConditionGrade;
import com.bidbuygo.listing.ListingDraft;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EligibilityServiceTest {

    private final EligibilityService service = new EligibilityService(null);
    private AuctionPolicy policy;
    private User trustedSeller;

    @BeforeEach
    void setUp() {
        policy = AuctionPolicy.defaults();
        trustedSeller = seller(true, 10);
    }

    @Test
    void validAuctionFromTrustedSellerIsEligible() {
        EligibilityResult result = service.evaluate(policy, trustedSeller, auction("collectibles", ConditionGrade.GOOD, 2, 10_000L));

        assertThat(result.eligible()).isTrue();
        assertThat(result.reasons()).isEmpty();
    }

    @Test
    void fixedListingIsAlwaysEligible() {
        ListingDraft fixed = new ListingDraft("Shoes", null, "shoes", ConditionGrade.FAIR, List.of(),
                ListingType.FIXED, 1_000L, null, null, null);

        EligibilityResult result = service.evaluate(policy, seller(false, 0), fixed);

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void unverifiedSellerFailsWhenVerificationRequired() {
        EligibilityResult result = service.evaluate(policy, seller(false, 10), validAuction());

        assertThat(result.reasons()).containsExactly("Seller must be verified to auction");
    }

    @Test
    void unverifiedSellerPassesWhenVerificationNotRequired() {
        policy.setRequireVerifiedSeller(false);

        assertThat(service.evaluate(policy, seller(false, 10), validAuction()).eligible()).isTrue();
    }

    @Test
    void tooFewCompletedOrdersFails() {
        EligibilityResult result = service.evaluate(policy, seller(true, 4), validAuction());

        assertThat(result.reasons()).containsExactly("Seller needs at least 5 completed orders to auction; has 4");
    }

    @Test
    void exactlyMinimumCompletedOrdersPasses() {
        assertThat(service.evaluate(policy, seller(true, 5), validAuction()).eligible()).isTrue();
    }

    @Test
    void disallowedCategoryFailsAndListsAllowedOnes() {
        EligibilityResult result = service.evaluate(policy, trustedSeller, auction("shoes", ConditionGrade.GOOD, 2, 10_000L));

        assertThat(result.reasons()).containsExactly(
                "Category 'shoes' is not auctionable; allowed: collectibles, handmade, limited-edition, open-box, pre-owned");
    }

    @Test
    void categoryComparisonIsCaseInsensitive() {
        EligibilityResult result = service.evaluate(policy, trustedSeller, auction("  CollectIBLES ", ConditionGrade.GOOD, 2, 10_000L));

        assertThat(result.eligible()).isTrue();
    }

    @Test
    void disallowedConditionFails() {
        policy.replaceAllowedConditions(List.of(ConditionGrade.NEW, ConditionGrade.LIKE_NEW));

        EligibilityResult result = service.evaluate(policy, trustedSeller, auction("collectibles", ConditionGrade.FAIR, 2, 10_000L));

        assertThat(result.reasons()).containsExactly("Condition FAIR is not auctionable; allowed: NEW, LIKE_NEW");
    }

    @Test
    void tooFewPhotosFails() {
        EligibilityResult result = service.evaluate(policy, trustedSeller, auction("collectibles", ConditionGrade.GOOD, 1, 10_000L));

        assertThat(result.reasons()).containsExactly("Auctions need at least 2 photos; got 1");
    }

    @Test
    void reserveBelowMinimumFails() {
        EligibilityResult result = service.evaluate(policy, trustedSeller, auction("collectibles", ConditionGrade.GOOD, 2, 9_999L));

        assertThat(result.reasons()).containsExactly("Reserve price must be at least ₹100.00; got ₹99.99");
    }

    @Test
    void everyFailingRuleIsReported() {
        policy.replaceAllowedConditions(List.of(ConditionGrade.NEW));

        EligibilityResult result = service.evaluate(policy, seller(false, 0), auction("shoes", ConditionGrade.FAIR, 0, 500L));

        assertThat(result.eligible()).isFalse();
        assertThat(result.reasons()).containsExactly(
                "Seller must be verified to auction",
                "Seller needs at least 5 completed orders to auction; has 0",
                "Category 'shoes' is not auctionable; allowed: collectibles, handmade, limited-edition, open-box, pre-owned",
                "Condition FAIR is not auctionable; allowed: NEW",
                "Auctions need at least 2 photos; got 0",
                "Reserve price must be at least ₹100.00; got ₹5.00");
    }

    private static User seller(boolean verified, int completedOrders) {
        User user = new User("seller@test.dev", "hash", Role.SELLER);
        user.setVerified(verified);
        user.setCompletedOrders(completedOrders);
        return user;
    }

    private static ListingDraft validAuction() {
        return auction("collectibles", ConditionGrade.GOOD, 2, 10_000L);
    }

    private static ListingDraft auction(String category, ConditionGrade condition, int photos, long reserve) {
        List<String> urls = java.util.stream.IntStream.range(0, photos).mapToObj(i -> "https://img.test/" + i).toList();
        return new ListingDraft("Item", null, category, condition, urls, ListingType.AUCTION, null,
                Math.min(reserve, 5_000L), reserve, Instant.now().plus(1, ChronoUnit.DAYS));
    }
}
