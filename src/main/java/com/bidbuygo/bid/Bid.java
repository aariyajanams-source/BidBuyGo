package com.bidbuygo.bid;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An accepted bid (rejected bids are never stored). Each accepted bid beats the previous one by at least the minimum
 * increment, so (listingId, amountPaise) is unique: a database backstop behind the listing lock.
 */
@Entity
@Table(name = "bids",
        uniqueConstraints = @UniqueConstraint(name = "uk_bids_listing_amount", columnNames = {"listingId", "amountPaise"}),
        indexes = @Index(name = "idx_bids_listing", columnList = "listingId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long listingId;

    @Column(nullable = false, updatable = false)
    private Long bidderId;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    Bid(Long listingId, Long bidderId, long amountPaise, Instant createdAt) {
        this.listingId = listingId;
        this.bidderId = bidderId;
        this.amountPaise = amountPaise;
        this.createdAt = createdAt;
    }
}
