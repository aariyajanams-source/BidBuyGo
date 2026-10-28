package com.bidbuygo.bid;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PlaceBidRequest(
        @NotNull(message = "is required")
        @Positive(message = "must be a positive number of paise")
        Long amount) {
}
