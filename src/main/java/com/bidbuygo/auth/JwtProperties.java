package com.bidbuygo.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from bidbuygo.jwt.*; the secret is a Base64-encoded HMAC key of at least 256 bits. */
@ConfigurationProperties(prefix = "bidbuygo.jwt")
public record JwtProperties(String secret, long expirationMinutes) {
}
