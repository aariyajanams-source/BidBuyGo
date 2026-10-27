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
import org.hibernate.annotations.Check;

/**
 * Balances are in paise. No @Version: concurrent writers are serialized with PESSIMISTIC_WRITE locks.
 * Balances have no setters; they change only through {@link #applyDelta}, which WalletService calls
 * alongside writing the matching WalletTransaction. The CHECK constraint is a last line of defense.
 */
@Entity
@Table(name = "wallets")
@Check(constraints = "available_balance >= 0 and held_balance >= 0")
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

    /** Applies signed changes to both balances, refusing any change that would leave either negative. */
    void applyDelta(long availableDelta, long heldDelta) {
        long newAvailable = Math.addExact(availableBalance, availableDelta);
        long newHeld = Math.addExact(heldBalance, heldDelta);
        if (newAvailable < 0) {
            throw new InsufficientFundsException(id, "available", availableBalance, -availableDelta);
        }
        if (newHeld < 0) {
            throw new InsufficientFundsException(id, "held", heldBalance, -heldDelta);
        }
        availableBalance = newAvailable;
        heldBalance = newHeld;
    }
}
