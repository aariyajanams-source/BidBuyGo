package com.bidbuygo.bid;

import java.time.Instant;

/** Result of placing a bid: the bid (bidder masked) plus the auction's end time, which anti-sniping may extend. */
public record PlacedBidResponse(Long id, Long listingId, long amountPaise, Instant createdAt, String bidder,
                                Instant endsAt, boolean extended) {

    static PlacedBidResponse from(Bid bid, String bidderEmail, Instant endsAt, boolean extended) {
        return new PlacedBidResponse(bid.getId(), bid.getListingId(), bid.getAmountPaise(), bid.getCreatedAt(),
                BidResponse.maskEmail(bidderEmail), endsAt, extended);
    }
}
