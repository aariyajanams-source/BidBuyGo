package com.bidbuygo.wallet;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TopupRequest(
        @NotNull(message = "is required")
        @Positive(message = "must be a positive number of paise")
        @Max(value = WalletService.MAX_TOPUP_PAISE, message = "must be at most 10000000 paise per top-up")
        Long amount) {
}
