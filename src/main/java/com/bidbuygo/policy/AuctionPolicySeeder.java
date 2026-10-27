package com.bidbuygo.policy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs in every profile, before the dev seeder, so auction eligibility always has a policy to read. */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
class AuctionPolicySeeder implements ApplicationRunner {

    private final AuctionPolicyService policyService;

    @Override
    public void run(ApplicationArguments args) {
        if (policyService.seedDefaultsIfMissing()) {
            log.info("Seeded default auction policy");
        }
    }
}
