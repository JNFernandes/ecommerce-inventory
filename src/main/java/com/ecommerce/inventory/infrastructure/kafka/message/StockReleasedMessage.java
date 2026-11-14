package com.ecommerce.inventory.infrastructure.kafka.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code inventory.stock-released}: a reservation was released because its order was
 * cancelled. Contract: {@code ecommerce-infra/docs/events.md}. Field order is the JSON field order.
 *
 * @param eventId unique id of this event; a republish keeps it
 * @param occurredAt when the stock was given back (equals {@code releasedAt})
 * @param aggregateId the reservation id; also the Kafka message key
 * @param version schema version, {@code 1}
 * @param orderId the cancelled order
 * @param items the consolidated quantities given back, sorted by product id
 * @param releasedAt when the stock was given back
 */
public record StockReleasedMessage(
        UUID eventId,
        Instant occurredAt,
        UUID aggregateId,
        int version,
        UUID orderId,
        List<ReleasedItem> items,
        Instant releasedAt) {

    /** Copies the items. */
    public StockReleasedMessage {
        items = List.copyOf(items);
    }
}
