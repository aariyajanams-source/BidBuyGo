package com.bidbuygo.policy;

import java.util.List;

import com.bidbuygo.listing.ConditionGrade;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** Full replacement of the policy (PUT semantics): every field is required. */
public record AuctionPolicyRequest(
        @NotEmpty(message = "must contain at least one category")
        List<@NotBlank(message = "must not be blank") String> allowedCategories,

        @NotEmpty(message = "must contain at least one condition")
        List<@NotNull(message = "must not be null") ConditionGrade> allowedConditions,

        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer minSellerCompletedOrders,

        @NotNull(message = "is required")
        Boolean requireVerifiedSeller,

        @NotNull(message = "is required")
        @PositiveOrZero(message = "must not be negative")
        Integer minPhotos,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0 paise")
        Long minReservePaise,

        @NotNull(message = "is required")
        @Positive(message = "must be greater than 0 paise")
        Long minIncrementPaise) {
}
