package com.bidbuygo.wallet;

public enum WalletTransactionType {
    /** External money in: available += amount. */
    DEPOSIT,
    /** Reserve funds (e.g. a bid): available -= amount, held += amount. */
    HOLD,
    /** Un-reserve funds (e.g. outbid): held -= amount, available += amount. */
    RELEASE,
    /** Money leaves for a purchase, from held (auction win) or available (buy now); the deltas say which. */
    CAPTURE,
    /** Internal money in (e.g. seller payout): available += amount. */
    CREDIT
}
