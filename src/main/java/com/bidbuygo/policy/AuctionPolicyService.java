package com.bidbuygo.policy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuctionPolicyService {

    private final AuctionPolicyRepository policyRepository;

    @Transactional(readOnly = true)
    public AuctionPolicy get() {
        return policyRepository.findById(AuctionPolicy.SINGLETON_ID)
                .orElseThrow(() -> new IllegalStateException("Auction policy has not been seeded"));
    }

    @Transactional(readOnly = true)
    public AuctionPolicyResponse getResponse() {
        return AuctionPolicyResponse.from(get());
    }

    @Transactional
    public AuctionPolicyResponse update(AuctionPolicyRequest request) {
        AuctionPolicy policy = get();
        policy.replaceAllowedCategories(request.allowedCategories());
        policy.replaceAllowedConditions(request.allowedConditions());
        policy.setMinSellerCompletedOrders(request.minSellerCompletedOrders());
        policy.setRequireVerifiedSeller(request.requireVerifiedSeller());
        policy.setMinPhotos(request.minPhotos());
        policy.setMinReservePaise(request.minReservePaise());
        policy.setMinIncrementPaise(request.minIncrementPaise());
        return AuctionPolicyResponse.from(policy);
    }

    /** Idempotent: an existing policy (possibly admin-edited) is never overwritten. */
    @Transactional
    public boolean seedDefaultsIfMissing() {
        if (policyRepository.existsById(AuctionPolicy.SINGLETON_ID)) {
            return false;
        }
        policyRepository.save(AuctionPolicy.defaults());
        return true;
    }
}
