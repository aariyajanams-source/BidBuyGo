package com.bidbuygo.user;

import java.util.Locale;
import java.util.Optional;

import com.bidbuygo.common.error.ApiException;
import com.bidbuygo.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final WalletService walletService;

    /**
     * Creates a user and their wallet. Does not apply registration rules (e.g. the ADMIN ban);
     * callers that take public input must enforce those first.
     */
    @Transactional
    public User create(String email, String rawPassword, Role role) {
        String normalized = normalizeEmail(email);
        if (userRepository.existsByEmail(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "Email already registered",
                    "An account with email " + normalized + " already exists");
        }
        User user = userRepository.save(new User(normalized, passwordEncoder.encode(rawPassword), role));
        walletService.createFor(user.getId());
        return user;
    }

    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(normalizeEmail(email));
    }

    @Transactional(readOnly = true)
    public User get(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found", "No user with id " + id));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incrementCompletedOrders(Long sellerId) {
        if (userRepository.incrementCompletedOrders(sellerId) != 1) {
            throw new IllegalStateException("No user with id " + sellerId);
        }
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
