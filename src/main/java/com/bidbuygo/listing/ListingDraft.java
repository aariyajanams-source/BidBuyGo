package com.bidbuygo.listing;

import java.time.Instant;
import java.util.List;

/**
 * Body of POST /listings and POST /listings/check. Validated by {@link ListingDraftValidator} rather than
 * bean validation, because which fields are required depends on the type and all reasons are reported together.
 */
public record ListingDraft(
        String title,
        String description,
        String category,
        ConditionGrade conditionGrade,
        List<String> photoUrls,
        ListingType type,
        Long fixedPrice,
        Long startPrice,
        Long reservePrice,
        Instant endsAt) {

    public List<String> photos() {
        return photoUrls == null ? List.of() : photoUrls;
    }
}
