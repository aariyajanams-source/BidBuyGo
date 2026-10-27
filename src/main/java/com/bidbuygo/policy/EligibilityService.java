package com.bidbuygo.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.bidbuygo.common.money.Money;
import com.bidbuygo.listing.ListingDraft;
import com.bidbuygo.listing.ListingType;
import com.bidbuygo.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EligibilityService {

    private final AuctionPolicyService policyService;

    public EligibilityResult check(User seller, ListingDraft draft) {
        if (draft.type() != ListingType.AUCTION) {
            return EligibilityResult.of(List.of());
        }
        return evaluate(policyService.get(), seller, draft);
    }

    /**
     * Applies every auction rule and collects all failures, so the seller sees everything to fix at once.
     * FIXED listings are always eligible.
     */
    public EligibilityResult evaluate(AuctionPolicy policy, User seller, ListingDraft draft) {
        if (draft.type() != ListingType.AUCTION) {
            return EligibilityResult.of(List.of());
        }
        List<String> reasons = new ArrayList<>();

        if (policy.isRequireVerifiedSeller() && !seller.isVerified()) {
            reasons.add("Seller must be verified to auction");
        }
        if (seller.getCompletedOrders() < policy.getMinSellerCompletedOrders()) {
            reasons.add("Seller needs at least %d completed orders to auction; has %d"
                    .formatted(policy.getMinSellerCompletedOrders(), seller.getCompletedOrders()));
        }
        if (!policy.allowsCategory(draft.category())) {
            String category = draft.category() == null ? "" : draft.category().trim();
            reasons.add("Category '%s' is not auctionable; allowed: %s"
                    .formatted(category, String.join(", ", policy.sortedCategories())));
        }
        if (draft.conditionGrade() == null || !policy.getAllowedConditions().contains(draft.conditionGrade())) {
            reasons.add("Condition %s is not auctionable; allowed: %s".formatted(draft.conditionGrade(),
                    policy.sortedConditions().stream().map(Enum::name).collect(Collectors.joining(", "))));
        }
        int photos = draft.photos().size();
        if (photos < policy.getMinPhotos()) {
            reasons.add("Auctions need at least %d photos; got %d".formatted(policy.getMinPhotos(), photos));
        }
        if (draft.reservePrice() == null || draft.reservePrice() < policy.getMinReservePaise()) {
            reasons.add("Reserve price must be at least %s; got %s".formatted(Money.format(policy.getMinReservePaise()),
                    draft.reservePrice() == null ? "none" : Money.format(draft.reservePrice())));
        }
        return EligibilityResult.of(reasons);
    }
}
