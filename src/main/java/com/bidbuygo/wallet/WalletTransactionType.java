package com.bidbuygo.wallet;

public enum WalletTransactionType {
    /** External money in: available += amount. */
    DEPOSIT,
    /** Reserve funds (e.g. a bid): available -= amount, held += amount. */
    HOLD,
    /** Un-reserve funds (e.g. outbid): held -= amount, available += amount. */
    RELEASE,
    /** Spend reserved funds (e.g. auction won): held -= amount. */
    CAPTURE,
    /** Internal money in (e.g. seller payout): available += amount. */
    CREDIT
}
