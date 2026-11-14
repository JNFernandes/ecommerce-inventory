package com.ecommerce.inventory.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Records that an order was cancelled before Inventory handled its placement (US-02 FR-010). A later
 * placement of that order reserves nothing. At most one marker exists per order.
 *
 * @param orderId the cancelled order
 * @param eventId the order-cancelled event that created the marker
 * @param createdAt when the marker was recorded
 */
public record CancellationMarker(OrderId orderId, UUID eventId, Instant createdAt) {

    /** Validates the marker. */
    public CancellationMarker {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
