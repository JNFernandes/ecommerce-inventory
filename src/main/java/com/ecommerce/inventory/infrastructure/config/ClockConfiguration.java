package com.ecommerce.inventory.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the clock used for every business timestamp, so tests can control time. */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /**
     * System clock in UTC.
     *
     * @return the UTC system clock
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
