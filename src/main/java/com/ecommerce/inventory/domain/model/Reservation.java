package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.event.StockReserved;
import com.ecommerce.inventory.domain.exception.InsufficientStockException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregate: the outcome of handling one order. At most one reservation exists per order (FR-013).
 * {@link #decide} is the only way to create one and holds every reservation rule.
 */
public final class Reservation {

    private final ReservationId id;
    private final OrderId orderId;
    private final ReservationStatus status;
    private final RequestedItems items;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<InventoryDomainEvent> domainEvents = new ArrayList<>();

    private Reservation(
            ReservationId id,
            OrderId orderId,
            ReservationStatus status,
            RequestedItems items,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
        this.items = Objects.requireNonNull(items, "items");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * Decides the reservation of an order, all or nothing: every item is checked against the locked
     * stock first, and stock is taken only when every item can be supplied.
     *
     * @param id new reservation id
     * @param orderId order being reserved
     * @param items consolidated requested items
     * @param lockedStock stock items locked for this decision, by product id; unknown products absent
     * @param now decision time
     * @return the reservation, with its domain event pending
     * @throws InsufficientStockException when an item is unknown or cannot be supplied (no stock is
     *     changed in that case)
     */
    public static Reservation decide(
            ReservationId id,
            OrderId orderId,
            RequestedItems items,
            Map<ProductId, StockItem> lockedStock,
            Instant now) {
        for (RequestedItem item : items.items()) {
            StockItem stock = lockedStock.get(item.productId());
            if (stock == null) {
                throw new InsufficientStockException(
                        item.productId().value(), item.quantity().value(), 0);
            }
            if (!stock.canSupply(item.quantity())) {
                throw new InsufficientStockException(
                        item.productId().value(), item.quantity().value(), stock.availableQuantity());
            }
        }
        for (RequestedItem item : items.items()) {
            lockedStock.get(item.productId()).reserve(item.quantity());
        }
        Reservation reservation = new Reservation(id, orderId, ReservationStatus.RESERVED, items, now, now);
        reservation.domainEvents.add(new StockReserved(id, orderId, items.items(), now));
        return reservation;
    }

    /**
     * Rebuilds a persisted reservation. Raises no events.
     *
     * @param id reservation id
     * @param orderId order id
     * @param status stored status
     * @param items stored items
     * @param createdAt creation time
     * @param updatedAt last update time
     * @return the restored reservation
     */
    public static Reservation restore(
            ReservationId id,
            OrderId orderId,
            ReservationStatus status,
            RequestedItems items,
            Instant createdAt,
            Instant updatedAt) {
        return new Reservation(id, orderId, status, items, createdAt, updatedAt);
    }

    /**
     * Returns the pending domain events and clears them, so each event is handled once.
     *
     * @return the pending events
     */
    public List<InventoryDomainEvent> pullDomainEvents() {
        List<InventoryDomainEvent> pending = List.copyOf(domainEvents);
        domainEvents.clear();
        return pending;
    }

    /**
     * Reservation id.
     *
     * @return the id
     */
    public ReservationId id() {
        return id;
    }

    /**
     * Order this reservation belongs to.
     *
     * @return the order id
     */
    public OrderId orderId() {
        return orderId;
    }

    /**
     * Current status.
     *
     * @return the status
     */
    public ReservationStatus status() {
        return status;
    }

    /**
     * Consolidated requested items.
     *
     * @return the items
     */
    public RequestedItems items() {
        return items;
    }

    /**
     * Creation time.
     *
     * @return when the reservation was decided
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Last update time.
     *
     * @return when the reservation last changed
     */
    public Instant updatedAt() {
        return updatedAt;
    }
}
