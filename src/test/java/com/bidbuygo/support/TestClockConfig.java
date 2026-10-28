package com.bidbuygo.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Picked up by component scanning (deliberately not @TestConfiguration, which would have to be imported per class and
 * would change the context cache key). Together with {@link IntegrationTest} this keeps the suite on one context.
 */
@Configuration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
