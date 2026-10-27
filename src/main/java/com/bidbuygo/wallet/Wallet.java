package com.bidbuygo.wallet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Balances are in paise. No @Version: concurrent writers are serialized with PESSIMISTIC_WRITE locks.
 * Balances have no setters; they change only through {@link #apply}, which WalletService calls
 * alongside writing the matching WalletTransaction.
 */
@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long userId;

    @Column(nullable = false)
    private long availableBalance = 0;

    @Column(nullable = false)
    private long heldBalance = 0;

    Wallet(Long userId) {
        this.userId = userId;
    }

    void apply(WalletTransactionType type, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive, got " + amount);
        }
        switch (type) {
            case DEPOSIT, CREDIT -> availableBalance = Math.addExact(availableBalance, amount);
            case HOLD -> {
                requireSufficient(availableBalance, amount, "available");
                availableBalance -= amount;
                heldBalance = Math.addExact(heldBalance, amount);
            }
            case RELEASE -> {
                requireSufficient(heldBalance, amount, "held");
                heldBalance -= amount;
                availableBalance = Math.addExact(availableBalance, amount);
            }
            case CAPTURE -> {
                requireSufficient(heldBalance, amount, "held");
                heldBalance -= amount;
            }
        }
    }

    private void requireSufficient(long balance, long amount, String bucket) {
        if (balance < amount) {
            throw new IllegalStateException("Wallet " + id + " has " + balance + " " + bucket + " paise, needs " + amount);
        }
    }
}
