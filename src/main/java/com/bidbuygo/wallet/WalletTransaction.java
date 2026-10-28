package com.bidbuygo.wallet;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Immutable ledger entry. {@code amount} is the positive size of the movement; {@code availableDelta} and
 * {@code heldDelta} are the signed changes it made, so summing a wallet's deltas from zero reproduces its balances.
 */
@Entity
@Table(name = "wallet_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private WalletTransactionType type;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(nullable = false, updatable = false)
    private long availableDelta;

    @Column(nullable = false, updatable = false)
    private long heldDelta;

    /** The listing this movement relates to; null for deposits. */
    @Column(updatable = false)
    private Long listingId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    WalletTransaction(Wallet wallet, WalletTransactionType type, long amount, long availableDelta, long heldDelta,
                      Long listingId, Instant createdAt) {
        this.wallet = wallet;
        this.type = type;
        this.amount = amount;
        this.availableDelta = availableDelta;
        this.heldDelta = heldDelta;
        this.listingId = listingId;
        this.createdAt = createdAt;
    }
}
