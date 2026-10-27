package com.bidbuygo.policy;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/policy")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminPolicyController {

    private final AuctionPolicyService policyService;

    @GetMapping
    public AuctionPolicyResponse get() {
        return policyService.getResponse();
    }

    @PutMapping
    public AuctionPolicyResponse update(@Valid @RequestBody AuctionPolicyRequest request) {
        return policyService.update(request);
    }
}
