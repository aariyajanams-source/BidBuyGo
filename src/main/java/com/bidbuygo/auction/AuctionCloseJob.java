package com.bidbuygo.auction;

import java.util.ArrayList;
import java.util.List;

import com.bidbuygo.listing.ListingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One pass of the close job: find due auctions, then settle each in its own transaction. Deliberately not
 * transactional itself. Always present (unlike {@link AuctionCloseScheduler}) so tests can run a pass directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionCloseJob {

    private final ListingService listingService;
    private final AuctionSettlementService settlementService;

    public record Result(List<Long> settled, List<Long> skipped, List<Long> failed) {
    }

    public Result runOnce() {
        List<Long> settled = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        for (Long listingId : listingService.findDueAuctionIds()) {
            try {
                if (settlementService.closeIfDue(listingId).isPresent()) {
                    settled.add(listingId);
                } else {
                    skipped.add(listingId);
                }
            } catch (RuntimeException e) {
                // One bad auction must not stop the rest; its transaction rolled back and the next pass retries it.
                log.error("Failed to settle auction {}", listingId, e);
                failed.add(listingId);
            }
        }
        return new Result(settled, skipped, failed);
    }
}
