package com.bidbuygo.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(JwtProperties properties) {
        // Throws WeakKeyException at startup if the key is shorter than 256 bits.
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.ttl = Duration.ofMinutes(properties.expirationMinutes());
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException if the token is malformed, tampered with or expired */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        try {
            return new AuthUser(Long.valueOf(claims.getSubject()), claims.get("email", String.class),
                    Role.valueOf(claims.get("role", String.class)));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("Token is missing required claims", e);
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}
