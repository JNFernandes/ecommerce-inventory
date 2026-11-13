package com.ecommerce.inventory.domain.event;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ReservationId;
import java.time.Instant;

/** A fact raised by a reservation decision. Each event maps to exactly one Kafka topic. */
public sealed interface InventoryDomainEvent permits StockReserved, StockRejected {

    /**
     * Reservation that raised the event; the {@code aggregateId} of the published message.
     *
     * @return the reservation id
     */
    ReservationId reservationId();

    /**
     * Order the reservation belongs to.
     *
     * @return the order id
     */
    OrderId orderId();

    /**
     * When the decision was made.
     *
     * @return the decision time
     */
    Instant occurredAt();
}
