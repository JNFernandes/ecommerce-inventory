package com.ecommerce.inventory.domain.event;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Every item of the order was reserved atomically.
 *
 * @param reservationId reservation that holds the stock
 * @param orderId order the stock is reserved for
 * @param items consolidated items, sorted by product id
 * @param reservedAt when the stock was reserved
 */
public record StockReserved(ReservationId reservationId, OrderId orderId, List<RequestedItem> items, Instant reservedAt)
        implements InventoryDomainEvent {

    /** Validates the event and copies the items. */
    public StockReserved {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reservedAt, "reservedAt");
        items = List.copyOf(items);
    }

    @Override
    public Instant occurredAt() {
        return reservedAt;
    }
}
