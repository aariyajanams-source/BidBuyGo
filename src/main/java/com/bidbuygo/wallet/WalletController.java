package com.bidbuygo.wallet;

import com.bidbuygo.auth.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public WalletResponse get(@AuthenticationPrincipal AuthUser principal) {
        return walletService.getForUser(principal.id());
    }

    @PostMapping("/topup")
    public WalletResponse topup(@AuthenticationPrincipal AuthUser principal, @Valid @RequestBody TopupRequest request) {
        return walletService.deposit(principal.id(), request.amount());
    }
}
