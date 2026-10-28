package com.bidbuygo.wallet;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.common.persistence.Locks;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns every balance change. The ledger operations (hold, release, captureHeld, chargeAvailable, credit) run in the
 * caller's transaction (MANDATORY) on wallets the caller locked with {@link #lockWallets}. Each writes one ledger row.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    public static final long MAX_TOPUP_PAISE = 10_000_000L;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    @Transactional
    public Wallet createFor(Long userId) {
        return walletRepository.save(new Wallet(userId));
    }

    @Transactional(readOnly = true)
    public WalletResponse getForUser(Long userId) {
        return WalletResponse.from(walletRepository.findByUserId(userId).orElseThrow(() -> walletNotFound(userId)));
    }

    /** Simulated external deposit. Locks the wallet, then records a DEPOSIT ledger entry. */
    @Transactional
    public WalletResponse deposit(Long userId, long amount) {
        if (amount <= 0 || amount > MAX_TOPUP_PAISE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid top-up amount",
                    "amount must be between 1 and " + MAX_TOPUP_PAISE + " paise");
        }
        Wallet wallet = lockWallets(List.of(userId)).get(userId);
        record(wallet, WalletTransactionType.DEPOSIT, amount, amount, 0, null);
        return WalletResponse.from(wallet);
    }

    /**
     * Locks the given users' wallets with PESSIMISTIC_WRITE in ascending wallet id order, so any two transactions
     * locking overlapping wallets acquire them in the same order and cannot deadlock. Call it after locking the
     * listing.
     *
     * @return wallets keyed by user id, locked and with balances re-read under the lock
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, Wallet> lockWallets(Collection<Long> userIds) {
        Set<Long> distinctUserIds = new LinkedHashSet<>(userIds);
        List<Long> walletIds = walletRepository.findIdsByUserIdIn(distinctUserIds).stream().sorted().toList();
        Map<Long, Wallet> byUserId = new HashMap<>();
        for (Long walletId : walletIds) {
            Wallet wallet = Locks.ensureWriteLocked(entityManager, walletRepository.findByIdForUpdate(walletId).orElseThrow());
            byUserId.put(wallet.getUserId(), wallet);
        }
        for (Long userId : distinctUserIds) {
            if (!byUserId.containsKey(userId)) {
                throw walletNotFound(userId);
            }
        }
        return byUserId;
    }

    /** available -> held, e.g. reserving a bid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction hold(Wallet wallet, long amount, Long listingId) {
        return record(wallet, WalletTransactionType.HOLD, amount, -amount, amount, listingId);
    }

    /** held -> available, e.g. when outbid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction release(Wallet wallet, long amount, Long listingId) {
        return record(wallet, WalletTransactionType.RELEASE, amount, amount, -amount, listingId);
    }

    /** Held money leaves the wallet, e.g. paying for a won auction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction captureHeld(Wallet wallet, long amount, Long listingId) {
        return record(wallet, WalletTransactionType.CAPTURE, amount, 0, -amount, listingId);
    }

    /** Available money leaves the wallet, e.g. a buy-now purchase. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction chargeAvailable(Wallet wallet, long amount, Long listingId) {
        return record(wallet, WalletTransactionType.CAPTURE, amount, -amount, 0, listingId);
    }

    /** Money arrives in available, e.g. a seller's payout. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction credit(Wallet wallet, long amount, Long listingId) {
        return record(wallet, WalletTransactionType.CREDIT, amount, amount, 0, listingId);
    }

    /** The only path that changes balances: mutate the locked wallet and write the matching ledger entry. */
    private WalletTransaction record(Wallet wallet, WalletTransactionType type, long amount,
                                     long availableDelta, long heldDelta, Long listingId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Ledger amount must be positive, got " + amount);
        }
        if (!Locks.isWriteLocked(entityManager, wallet)) {
            throw new IllegalStateException("Wallet " + wallet.getId() + " must be locked via lockWallets before a "
                    + type + " entry (current lock mode: " + entityManager.getLockMode(wallet) + ")");
        }
        wallet.applyDelta(availableDelta, heldDelta);
        return transactionRepository.save(
                new WalletTransaction(wallet, type, amount, availableDelta, heldDelta, listingId, clock.instant()));
    }

    private static ApiException walletNotFound(Long userId) {
        return new ApiException(HttpStatus.NOT_FOUND, "Wallet not found", "No wallet for user " + userId);
    }
}
