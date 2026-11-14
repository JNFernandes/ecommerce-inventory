package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.port.ProcessedOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Persistence model of {@code processed_events}. Never leaves this package. */
@Entity
@Table(name = "processed_events")
class ProcessedEventEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private ProcessedOutcome outcome;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "outcome_event_id")
    private UUID outcomeEventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {}

    ProcessedEventEntity(
            UUID eventId,
            String topic,
            ProcessedOutcome outcome,
            UUID reservationId,
            UUID outcomeEventId,
            Instant processedAt) {
        this.eventId = eventId;
        this.topic = topic;
        this.outcome = outcome;
        this.reservationId = reservationId;
        this.outcomeEventId = outcomeEventId;
        this.processedAt = processedAt;
    }

    UUID getOutcomeEventId() {
        return outcomeEventId;
    }

    UUID getEventId() {
        return eventId;
    }

    String getTopic() {
        return topic;
    }

    ProcessedOutcome getOutcome() {
        return outcome;
    }

    UUID getReservationId() {
        return reservationId;
    }

    Instant getProcessedAt() {
        return processedAt;
    }
}
