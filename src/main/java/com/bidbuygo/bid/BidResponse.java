package com.bidbuygo.bid;

import java.time.Instant;

/** Public view of a bid: the bidder appears only as a masked email, never an id or full address. */
public record BidResponse(Long id, Long listingId, long amountPaise, Instant createdAt, String bidder) {

    static BidResponse from(Bid bid, String bidderEmail) {
        return new BidResponse(bid.getId(), bid.getListingId(), bid.getAmountPaise(), bid.getCreatedAt(),
                maskEmail(bidderEmail));
    }

    /** "buyer1@bidbuygo.dev" -> "b***1@bidbuygo.dev"; local parts of 1-2 chars keep only the first character. */
    static String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "***";
        }
        int at = email.indexOf('@');
        String local = at < 0 ? email : email.substring(0, at);
        String domain = at < 0 ? "" : email.substring(at);
        if (local.isEmpty()) {
            return "***" + domain;
        }
        String masked = local.length() <= 2
                ? local.charAt(0) + "***"
                : local.charAt(0) + "***" + local.charAt(local.length() - 1);
        return masked + domain;
    }
}
