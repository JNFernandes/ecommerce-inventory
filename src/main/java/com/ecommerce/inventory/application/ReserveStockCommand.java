package com.ecommerce.inventory.application;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.RequestedItem;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Request to reserve stock for a placed order.
 *
 * @param eventId id of the inbound event, used for idempotency
 * @param topic topic the event was consumed from
 * @param orderId the order to reserve stock for
 * @param items order lines as received (not yet consolidated)
 */
public record ReserveStockCommand(UUID eventId, String topic, OrderId orderId, List<RequestedItem> items) {

    /** Validates the command and copies the items. */
    public ReserveStockCommand {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(orderId, "orderId");
        items = List.copyOf(items);
    }
}
