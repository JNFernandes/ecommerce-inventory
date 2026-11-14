package com.ecommerce.inventory.application.port;

import java.util.Objects;
import java.util.UUID;

/**
 * An outbound dead letter claimed by the retry worker (US-03).
 *
 * @param id row id
 * @param message the stored message, republished unchanged
 * @param retryCount worker attempts made so far
 */
public record OutboundDeadLetter(UUID id, OutboundMessage message, int retryCount) {

    /** Validates the dead letter. */
    public OutboundDeadLetter {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(message, "message");
        if (retryCount < 0) {
            throw new IllegalArgumentException("retry count must not be negative, was " + retryCount);
        }
    }
}
