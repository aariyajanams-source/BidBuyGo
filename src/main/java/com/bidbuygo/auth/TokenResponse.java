package com.bidbuygo.auth;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
