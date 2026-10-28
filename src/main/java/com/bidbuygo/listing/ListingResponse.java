package com.bidbuygo.listing;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * viewerIsHighBidder is null when the request has no token.
 * reservePrice is present only for the listing's seller and admins (the field is omitted otherwise); everyone gets
 * reserveMet. minNextBid is the lowest bid that would be accepted, null unless the auction is open for bids.
 * Build it with ListingService.toResponse, which decides what the viewer may see.
 */
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
        @JsonInclude(JsonInclude.Include.NON_NULL) Long reservePrice,
        Boolean reserveMet,
        Instant endsAt,
        Long currentHighBid,
        Long highBidderId,
        int bidCount,
        Boolean viewerIsHighBidder,
        CloseReason closeReason,
        Long minNextBid) {

    /** Must be called inside a transaction: photoUrls is lazily loaded. */
    static ListingResponse from(Listing l, Boolean viewerIsHighBidder, boolean showReserve, Long minNextBid) {
        boolean auction = l.getType() == ListingType.AUCTION;
        return new ListingResponse(l.getId(), l.getSellerId(), l.getTitle(), l.getDescription(), l.getCategory(),
                l.getConditionGrade(), List.copyOf(l.getPhotoUrls()), l.getType(), l.getStatus(), l.getCreatedAt(),
                l.getRejectionReason(), l.getFixedPrice(), l.getStartPrice(),
                showReserve ? l.getReservePrice() : null, auction ? l.isReserveMet() : null, l.getEndsAt(),
                l.getCurrentHighBid(), l.getHighBidderId(), l.getBidCount(), viewerIsHighBidder, l.getCloseReason(),
                minNextBid);
    }
}
