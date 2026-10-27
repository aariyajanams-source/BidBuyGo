package com.bidbuygo.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "is required") String email,
        @NotBlank(message = "is required") String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}
