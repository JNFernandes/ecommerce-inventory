package com.ecommerce.inventory.application;

import java.time.Duration;
import java.util.Objects;

/**
 * How a message that failed to publish is scheduled for redelivery (FR-023). Provided by
 * configuration so the application layer does not depend on infrastructure.
 *
 * @param firstRetryDelay delay between the failed publish and the first scheduled retry
 */
public record DeadLetterPolicy(Duration firstRetryDelay) {

    /** Validates the policy. */
    public DeadLetterPolicy {
        Objects.requireNonNull(firstRetryDelay, "firstRetryDelay");
        if (firstRetryDelay.isNegative()) {
            throw new IllegalArgumentException("firstRetryDelay must not be negative");
        }
    }
}
