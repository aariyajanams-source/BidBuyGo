package com.bidbuygo.order;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A completed sale. Listings are single items, so listingId is unique: the database refuses a second sale. */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_buyer", columnList = "buyerId"),
        @Index(name = "idx_orders_seller", columnList = "sellerId")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private Long listingId;

    @Column(nullable = false, updatable = false)
    private Long buyerId;

    @Column(nullable = false, updatable = false)
    private Long sellerId;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private OrderType type;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    Order(Long listingId, Long buyerId, Long sellerId, long amountPaise, OrderType type, Instant createdAt) {
        this.listingId = listingId;
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.amountPaise = amountPaise;
        this.type = type;
        this.createdAt = createdAt;
    }
}
