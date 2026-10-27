package com.bidbuygo.auth;

import com.bidbuygo.user.Role;

/** The authenticated principal, built from a verified JWT. */
public record AuthUser(Long id, String email, Role role) {
}
