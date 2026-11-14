package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.domain.model.ReservationId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Record that an inbound event was handled, used for consumer idempotency (US-01 FR-015, US-02 FR-014).
 *
 * @param eventId inbound event id
 * @param topic topic the event was consumed from
 * @param outcome what processing produced
 * @param reservationId reservation created, found or released by the event; empty when none exists
 *     (early cancellation, placement of an already cancelled order)
 * @param outcomeEventId event id of the message the event produced, republished on redelivery; present
 *     only for {@link ProcessedOutcome#RESERVATION_DECIDED} and {@link ProcessedOutcome#STOCK_RELEASED}
 * @param processedAt when it was processed
 */
public record ProcessedEvent(
        UUID eventId,
        String topic,
        ProcessedOutcome outcome,
        Optional<ReservationId> reservationId,
        Optional<UUID> outcomeEventId,
        Instant processedAt) {

    /** Validates the record. */
    public ProcessedEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(outcomeEventId, "outcomeEventId");
        Objects.requireNonNull(processedAt, "processedAt");
        if (outcomeEventId.isPresent() != outcome.publishesMessage()) {
            throw new IllegalArgumentException(
                    "An outcome event id is required exactly for outcomes that publish a message, not for " + outcome);
        }
    }
}
