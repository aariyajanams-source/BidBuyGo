package com.bidbuygo.auth;

import com.bidbuygo.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "is required")
        @Email(message = "must be a valid email address")
        String email,

        // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated.
        @NotNull(message = "is required")
        @Size(min = 8, max = 72, message = "must be between 8 and 72 characters")
        String password,

        @NotNull(message = "is required (BUYER or SELLER)")
        Role role) {

    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", role=" + role + "]";
    }
}
