package com.bidbuygo.auth;

import java.util.Set;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.user.Role;
import com.bidbuygo.user.User;
import com.bidbuygo.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Set<Role> SELF_REGISTERABLE = Set.of(Role.BUYER, Role.SELLER);

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public User register(RegisterRequest request) {
        if (!SELF_REGISTERABLE.contains(request.role())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Registration rejected",
                    "role " + request.role() + " cannot be self-registered; choose BUYER or SELLER");
        }
        return userService.create(request.email(), request.password(), request.role());
    }

    public TokenResponse login(LoginRequest request) {
        User user = userService.findByEmail(request.email())
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login failed",
                        "Email or password is incorrect"));
        return new TokenResponse(jwtService.issue(user), "Bearer", jwtService.ttlSeconds());
    }
}
