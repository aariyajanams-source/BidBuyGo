package com.bidbuygo.listing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectListingRequest(
        @NotBlank(message = "is required")
        @Size(max = 500, message = "must be at most 500 characters")
        String reason) {
}
