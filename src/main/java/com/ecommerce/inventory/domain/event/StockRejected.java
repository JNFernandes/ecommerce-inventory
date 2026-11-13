package com.ecommerce.inventory.domain.event;

import com.ecommerce.inventory.domain.model.ItemFailure;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.RejectionReason;
import com.ecommerce.inventory.domain.model.ReservationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * At least one item of the order could not be reserved, and nothing was reserved.
 *
 * @param reservationId the rejected reservation
 * @param orderId the order that was rejected
 * @param reason why it was rejected
 * @param failures failing items only, sorted by product id
 * @param rejectedAt when the order was rejected
 */
public record StockRejected(
        ReservationId reservationId,
        OrderId orderId,
        RejectionReason reason,
        List<ItemFailure> failures,
        Instant rejectedAt)
        implements InventoryDomainEvent {

    /** Validates the event and copies the failures. */
    public StockRejected {
        Objects.requireNonNull(reservationId, "reservationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(rejectedAt, "rejectedAt");
        failures = List.copyOf(failures);
    }

    @Override
    public Instant occurredAt() {
        return rejectedAt;
    }
}
