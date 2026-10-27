package com.bidbuygo.wallet;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.common.money.Money;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Thrown when a ledger operation would make a wallet balance negative. Rolls back the caller's transaction. */
@Getter
public class InsufficientFundsException extends ApiException {

    private final Long walletId;
    private final long balance;
    private final long required;

    InsufficientFundsException(Long walletId, String bucket, long balance, long required) {
        super(HttpStatus.BAD_REQUEST, "Insufficient funds",
                "Wallet %d has %s %s; %s required".formatted(walletId, Money.format(balance), bucket, Money.format(required)));
        this.walletId = walletId;
        this.balance = balance;
        this.required = required;
    }
}
