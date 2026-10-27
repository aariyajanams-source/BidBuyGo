package com.bidbuygo.wallet;

import com.bidbuygo.common.error.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WalletService {

    public static final long MAX_TOPUP_PAISE = 10_000_000L;

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;

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
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId).orElseThrow(() -> walletNotFound(userId));
        record(wallet, WalletTransactionType.DEPOSIT, amount);
        return WalletResponse.from(wallet);
    }

    /** The only path that changes balances: mutate the locked wallet and write the matching ledger entry. */
    private void record(Wallet wallet, WalletTransactionType type, long amount) {
        wallet.apply(type, amount);
        transactionRepository.save(new WalletTransaction(wallet, type, amount));
    }

    private static ApiException walletNotFound(Long userId) {
        return new ApiException(HttpStatus.NOT_FOUND, "Wallet not found", "No wallet for user " + userId);
    }
}
