package com.bidbuygo.listing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Structural validation of a draft (400). Auction eligibility (422) is a separate step in EligibilityService. */
final class ListingDraftValidator {

    static final Duration MAX_AUCTION_DURATION = Duration.ofDays(14);
    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_DESCRIPTION_LENGTH = 5000;

    private ListingDraftValidator() {
    }

    static List<String> validate(ListingDraft draft, Instant now) {
        List<String> reasons = new ArrayList<>();

        if (isBlank(draft.title())) {
            reasons.add("title is required");
        } else if (draft.title().trim().length() > MAX_TITLE_LENGTH) {
            reasons.add("title must be at most " + MAX_TITLE_LENGTH + " characters");
        }
        if (draft.description() != null && draft.description().length() > MAX_DESCRIPTION_LENGTH) {
            reasons.add("description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        if (isBlank(draft.category())) {
            reasons.add("category is required");
        }
        if (draft.conditionGrade() == null) {
            reasons.add("conditionGrade is required");
        }
        if (draft.photos().stream().anyMatch(url -> isBlank(url) || !isHttpUrl(url.trim()))) {
            reasons.add("photoUrls must all be http(s) URLs");
        }

        if (draft.type() == null) {
            reasons.add("type is required (FIXED or AUCTION)");
        } else if (draft.type() == ListingType.FIXED) {
            validateFixed(draft, reasons);
        } else {
            validateAuction(draft, now, reasons);
        }
        return reasons;
    }

    private static void validateFixed(ListingDraft draft, List<String> reasons) {
        if (draft.fixedPrice() == null || draft.fixedPrice() <= 0) {
            reasons.add("fixedPrice must be greater than 0 paise for FIXED listings");
        }
        if (draft.startPrice() != null || draft.reservePrice() != null || draft.endsAt() != null) {
            reasons.add("startPrice, reservePrice and endsAt apply only to AUCTION listings");
        }
    }

    private static void validateAuction(ListingDraft draft, Instant now, List<String> reasons) {
        if (draft.fixedPrice() != null) {
            reasons.add("fixedPrice applies only to FIXED listings");
        }
        if (draft.startPrice() == null || draft.startPrice() <= 0) {
            reasons.add("startPrice must be greater than 0 paise for AUCTION listings");
        }
        if (draft.reservePrice() == null) {
            reasons.add("reservePrice is required for AUCTION listings");
        } else if (draft.startPrice() != null && draft.reservePrice() < draft.startPrice()) {
            reasons.add("reservePrice must be at least startPrice");
        }
        if (draft.endsAt() == null) {
            reasons.add("endsAt is required for AUCTION listings");
        } else if (!draft.endsAt().isAfter(now)) {
            reasons.add("endsAt must be in the future");
        } else if (draft.endsAt().isAfter(now.plus(MAX_AUCTION_DURATION))) {
            reasons.add("endsAt must be at most 14 days from now");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("https://") || value.startsWith("http://");
    }
}
