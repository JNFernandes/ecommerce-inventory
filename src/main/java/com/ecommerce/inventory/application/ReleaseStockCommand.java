package com.ecommerce.inventory.application;

import com.ecommerce.inventory.domain.model.OrderId;
import java.util.Objects;
import java.util.UUID;

/**
 * Request to release the stock of a cancelled order.
 *
 * @param eventId id of the inbound order-cancelled event, used for idempotency
 * @param topic topic the event was consumed from
 * @param orderId the cancelled order
 */
public record ReleaseStockCommand(UUID eventId, String topic, OrderId orderId) {

    /** Validates the command. */
    public ReleaseStockCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(orderId, "orderId");
    }
}
