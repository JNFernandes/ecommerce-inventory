package com.ecommerce.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Persistence model of {@code cancellation_markers}. Never leaves this package. */
@Entity
@Table(name = "cancellation_markers")
class CancellationMarkerEntity {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CancellationMarkerEntity() {}

    CancellationMarkerEntity(UUID orderId, UUID eventId, Instant createdAt) {
        this.orderId = orderId;
        this.eventId = eventId;
        this.createdAt = createdAt;
    }
}
