package com.bidbuygo.bid;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The concurrency proof on a real Postgres 16 (row locks, MVCC), run by failsafe in the verify phase.
 * Skipped, not failed, when Docker is unavailable. Uses the postgres profile, so the dev seed data never runs;
 * only the auction policy is seeded, and the proof creates its own users. Its own JVM and database, so the
 * single-context rule for {@code IntegrationTest} doesn't apply here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "bidbuygo.scheduler.enabled=false")
@ActiveProfiles("postgres")
class ConcurrentBiddingProofPostgresIT extends ConcurrentBiddingProof {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Override
    String database() {
        return "PostgreSQL 16 (Testcontainers)";
    }
}
