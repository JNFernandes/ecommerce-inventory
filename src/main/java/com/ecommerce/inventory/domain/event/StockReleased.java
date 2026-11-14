package com.ecommerce.inventory.domain.event;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A reservation was released because its order was cancelled; its stock is available again.
 *
 * @param reservationId the released reservation
 * @param orderId the cancelled order
 * @param items the consolidated quantities given back, sorted by product id
 * @param releasedAt when the stock was given back
 */
public record StockReleased(ReservationId reservationId, OrderId orderId, List<RequestedItem> items, Instant releasedAt)
        implements InventoryDomainEvent {

    /** Validates the event and copies the items. */
    public StockReleased {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(releasedAt, "releasedAt");
        items = List.copyOf(items);
    }

    @Override
    public Instant occurredAt() {
        return releasedAt;
    }
}
