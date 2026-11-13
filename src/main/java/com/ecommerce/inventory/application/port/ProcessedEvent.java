package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.domain.model.ReservationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Record that an inbound event was handled, used for consumer idempotency (FR-015).
 *
 * @param eventId inbound event id
 * @param topic topic the event was consumed from
 * @param outcome what processing produced
 * @param reservationId reservation created by, or found for, the event
 * @param processedAt when it was processed
 */
public record ProcessedEvent(
        UUID eventId, String topic, ProcessedOutcome outcome, ReservationId reservationId, Instant processedAt) {

    /** Validates the record. */
    public ProcessedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(processedAt, "processedAt");
    }
}
