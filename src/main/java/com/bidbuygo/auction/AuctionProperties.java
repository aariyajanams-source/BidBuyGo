package com.bidbuygo.auction;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bound from bidbuygo.auction.*. */
@ConfigurationProperties(prefix = "bidbuygo.auction")
public record AuctionProperties(
        /* A bid with at most this much time left extends the auction. */
        @DefaultValue("30") long snipeWindowSeconds,
        /* How far each late bid pushes endsAt out. */
        @DefaultValue("30") long extensionSeconds,
        /* Delay between runs of the close job. */
        @DefaultValue("5000") long closePollMs) {

    public Duration snipeWindow() {
        return Duration.ofSeconds(snipeWindowSeconds);
    }

    public Duration extension() {
        return Duration.ofSeconds(extensionSeconds);
    }
}
