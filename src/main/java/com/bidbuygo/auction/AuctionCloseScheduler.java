package com.bidbuygo.auction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Runs the close job on a timer. Disabled with bidbuygo.scheduler.enabled=false (tests do this). */
@Slf4j
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bidbuygo.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class AuctionCloseScheduler {

    private final AuctionCloseJob job;

    @Scheduled(fixedDelayString = "${bidbuygo.auction.close-poll-ms:5000}")
    public void closeDueAuctions() {
        AuctionCloseJob.Result result = job.runOnce();
        if (!result.settled().isEmpty() || !result.failed().isEmpty()) {
            log.info("Close job: settled {}, failed {}", result.settled(), result.failed());
        }
    }
}
