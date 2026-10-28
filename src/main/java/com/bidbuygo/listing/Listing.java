package com.bidbuygo.listing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.BatchSize;

/** A single item for sale. Money fields are paise; FIXED uses fixedPrice, AUCTION uses the auction fields. */
@Entity
@Table(name = "listings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Listing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = ListingDraftValidator.MAX_TITLE_LENGTH)
    private String title;

    @Column(length = ListingDraftValidator.MAX_DESCRIPTION_LENGTH)
    private String description;

    /** Stored trimmed and lower-cased so filters and policy checks are case-insensitive. */
    @Column(nullable = false, length = 64)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ConditionGrade conditionGrade;

    @ElementCollection
    @CollectionTable(name = "listing_photos", joinColumns = @JoinColumn(name = "listing_id"))
    @OrderColumn(name = "position")
    @Column(name = "url", nullable = false, length = 2048)
    @BatchSize(size = 50)
    private List<String> photoUrls = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ListingType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ListingStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(length = 500)
    private String rejectionReason;

    private Long fixedPrice;

    private Long startPrice;

    private Long reservePrice;

    private Instant endsAt;

    private Long currentHighBid;

    private Long highBidderId;

    /** Accepted bids; kept here (updated under the listing lock) so listing reads don't need the bid table. */
    @Column(nullable = false)
    private int bidCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CloseReason closeReason;

    static Listing create(Long sellerId, ListingDraft draft, Instant now) {
        Listing listing = new Listing();
        listing.sellerId = sellerId;
        listing.title = draft.title().trim();
        listing.description = draft.description();
        listing.category = normalizeCategory(draft.category());
        listing.conditionGrade = draft.conditionGrade();
        draft.photos().forEach(url -> listing.photoUrls.add(url.trim()));
        listing.type = draft.type();
        listing.status = ListingStatus.ACTIVE;
        listing.createdAt = now;
        if (draft.type() == ListingType.FIXED) {
            listing.fixedPrice = draft.fixedPrice();
        } else {
            listing.startPrice = draft.startPrice();
            listing.reservePrice = draft.reservePrice();
            listing.endsAt = draft.endsAt();
        }
        return listing;
    }

    /**
     * The lowest amount BidService.placeBid accepts: the start price until the first bid, then the current high bid
     * plus the policy's increment. The only place this rule lives; responses and bid validation both call it.
     */
    public long minimumNextBid(long minIncrementPaise) {
        return currentHighBid == null ? startPrice : currentHighBid + minIncrementPaise;
    }

    /** An ACTIVE auction that has not reached endsAt; bids are only accepted then. */
    public boolean isOpenForBids(Instant now) {
        return type == ListingType.AUCTION && status == ListingStatus.ACTIVE && endsAt.isAfter(now);
    }

    /** Whether the leading bid would sell the item at settlement. Auctions only. */
    public boolean isReserveMet() {
        return highBidderId != null && currentHighBid >= reservePrice;
    }

    public static String normalizeCategory(String category) {
        return category.trim().toLowerCase(Locale.ROOT);
    }

    // The state changes below require the caller to hold the listing's PESSIMISTIC_WRITE lock
    // (see ListingService.lockForUpdate).

    public void markSold() {
        this.status = ListingStatus.SOLD;
        this.closeReason = CloseReason.SOLD;
    }

    /** Ends an auction without a sale. */
    public void closeUnsold(CloseReason reason) {
        if (reason == CloseReason.SOLD) {
            throw new IllegalArgumentException("Use markSold for a sale");
        }
        this.status = ListingStatus.CLOSED;
        this.closeReason = reason;
    }

    /** Anti-sniping: push the end of an auction out by the given amount. */
    public void extendEndsAt(Duration extension) {
        this.endsAt = this.endsAt.plus(extension);
    }

    void reject(String reason) {
        this.status = ListingStatus.REJECTED;
        this.rejectionReason = reason;
    }

    /** Records an accepted bid as the new leader. */
    public void acceptBid(Long bidderId, long amount) {
        this.highBidderId = bidderId;
        this.currentHighBid = amount;
        this.bidCount++;
    }
}
