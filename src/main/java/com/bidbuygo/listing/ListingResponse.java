package com.bidbuygo.listing;

import java.time.Instant;
import java.util.List;

public record ListingResponse(
        Long id,
        Long sellerId,
        String title,
        String description,
        String category,
        ConditionGrade conditionGrade,
        List<String> photoUrls,
        ListingType type,
        ListingStatus status,
        Instant createdAt,
        String rejectionReason,
        Long fixedPrice,
        Long startPrice,
        Long reservePrice,
        Instant endsAt,
        Long currentHighBid,
        Long highBidderId) {

    /** Must be called inside a transaction: photoUrls is lazily loaded. */
    static ListingResponse from(Listing l) {
        return new ListingResponse(l.getId(), l.getSellerId(), l.getTitle(), l.getDescription(), l.getCategory(),
                l.getConditionGrade(), List.copyOf(l.getPhotoUrls()), l.getType(), l.getStatus(), l.getCreatedAt(),
                l.getRejectionReason(), l.getFixedPrice(), l.getStartPrice(), l.getReservePrice(), l.getEndsAt(),
                l.getCurrentHighBid(), l.getHighBidderId());
    }
}
