package com.ecommerce.inventory.infrastructure.kafka.message;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of {@code inventory.stock-rejected}: at least one item could not be reserved and nothing was
 * reserved. Contract: {@code ecommerce-infra/docs/events.md}. Field order is the JSON field order.
 *
 * @param eventId unique id of this event; a republish keeps it
 * @param occurredAt when the order was rejected (equals {@code rejectedAt})
 * @param aggregateId the reservation id; also the Kafka message key
 * @param version schema version, {@code 1}
 * @param orderId the order that was rejected
 * @param reason {@code insufficient stock}, {@code unknown product} or
 *     {@code unknown product and insufficient stock}
 * @param items the failing items only, sorted by product id
 * @param rejectedAt when the order was rejected
 */
public record StockRejectedMessage(
        UUID eventId,
        Instant occurredAt,
        UUID aggregateId,
        int version,
        UUID orderId,
        String reason,
        List<RejectedItem> items,
        Instant rejectedAt) {

    /** Copies the items. */
    public StockRejectedMessage {
        items = List.copyOf(items);
    }
}
