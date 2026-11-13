package com.ecommerce.inventory.infrastructure.kafka.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code inventory.stock-reserved}: all items of the order were reserved atomically.
 * Contract: {@code ecommerce-infra/docs/events.md}. Field order is the JSON field order.
 *
 * @param eventId unique id of this event; a republish keeps it
 * @param occurredAt when the stock was reserved (equals {@code reservedAt})
 * @param aggregateId the reservation id; also the Kafka message key
 * @param version schema version, {@code 1}
 * @param orderId the order the stock is reserved for
 * @param items reserved items, consolidated and sorted by product id
 * @param reservedAt when the stock was reserved
 */
public record StockReservedMessage(
        UUID eventId,
        Instant occurredAt,
        UUID aggregateId,
        int version,
        UUID orderId,
        List<ReservedItem> items,
        Instant reservedAt) {

    /** Copies the items. */
    public StockReservedMessage {
        items = List.copyOf(items);
    }
}
