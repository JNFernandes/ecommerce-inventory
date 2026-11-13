package com.ecommerce.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

/** Persistence model of {@code outcome_messages}. Never leaves this package. */
@Entity
@Table(name = "outcome_messages")
class OutcomeMessageEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "reservation_id", nullable = false)
    private UUID reservationId;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OutcomeMessageEntity() {}

    OutcomeMessageEntity(
            UUID eventId, UUID reservationId, String topic, String messageKey, String payload, Instant createdAt) {
        this.eventId = eventId;
        this.reservationId = reservationId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    UUID getEventId() {
        return eventId;
    }

    String getTopic() {
        return topic;
    }

    String getMessageKey() {
        return messageKey;
    }

    String getPayload() {
        return payload;
    }
}
