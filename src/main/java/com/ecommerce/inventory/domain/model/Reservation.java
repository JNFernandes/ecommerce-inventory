package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.event.StockRejected;
import com.ecommerce.inventory.domain.event.StockReserved;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregate: the outcome of handling one order. At most one reservation exists per order (FR-013).
 * {@link #decide} is the only way to create one and holds every reservation rule (FR-005 to FR-008).
 */
public final class Reservation {

    private final ReservationId id;
    private final OrderId orderId;
    private final ReservationStatus status;
    private final RequestedItems items;
    private final Rejection rejection;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<InventoryDomainEvent> domainEvents = new ArrayList<>();

    private Reservation(
            ReservationId id,
            OrderId orderId,
            ReservationStatus status,
            RequestedItems items,
            Rejection rejection,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
        this.items = Objects.requireNonNull(items, "items");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if ((status == ReservationStatus.REJECTED) != (rejection != null)) {
            throw new IllegalArgumentException("A reservation has a rejection if and only if it is REJECTED");
        }
        this.rejection = rejection;
    }

    /**
     * Decides the reservation of an order, all or nothing. Every item is checked against the locked
     * stock first. If any item is unknown or insufficient the order is rejected and no stock is
     * touched; otherwise every item is reserved.
     *
     * @param id new reservation id
     * @param orderId order being reserved
     * @param items consolidated requested items
     * @param lockedStock stock items locked for this decision, by product id; unknown products absent
     * @param now decision time
     * @return the reserved or rejected reservation, with its domain event pending
     */
    public static Reservation decide(
            ReservationId id,
            OrderId orderId,
            RequestedItems items,
            Map<ProductId, StockItem> lockedStock,
            Instant now) {
        List<ItemFailure> failures = new ArrayList<>();
        for (RequestedItem item : items.items()) {
            StockItem stock = lockedStock.get(item.productId());
            long requested = item.quantity().value();
            if (stock == null) {
                failures.add(new ItemFailure(item.productId(), requested, 0, FailureKind.UNKNOWN_PRODUCT));
            } else if (!stock.canSupply(item.quantity())) {
                failures.add(new ItemFailure(
                        item.productId(), requested, stock.availableQuantity(), FailureKind.INSUFFICIENT_STOCK));
            }
        }
        if (!failures.isEmpty()) {
            return rejected(id, orderId, items, failures, now);
        }
        for (RequestedItem item : items.items()) {
            lockedStock.get(item.productId()).reserve(item.quantity());
        }
        Reservation reservation = new Reservation(id, orderId, ReservationStatus.RESERVED, items, null, now, now);
        reservation.domainEvents.add(new StockReserved(id, orderId, items.items(), now));
        return reservation;
    }

    private static Reservation rejected(
            ReservationId id, OrderId orderId, RequestedItems items, List<ItemFailure> failures, Instant now) {
        Set<FailureKind> kinds = EnumSet.noneOf(FailureKind.class);
        failures.forEach(failure -> kinds.add(failure.kind()));
        Rejection rejection = new Rejection(RejectionReason.from(kinds), failures);
        Reservation reservation = new Reservation(id, orderId, ReservationStatus.REJECTED, items, rejection, now, now);
        reservation.domainEvents.add(new StockRejected(id, orderId, rejection.reason(), rejection.failures(), now));
        return reservation;
    }

    /**
     * Rebuilds a persisted reserved reservation. Raises no events.
     *
     * @param id reservation id
     * @param orderId order id
     * @param status stored status; must not be {@code REJECTED} (use {@link #restoreRejected})
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
        return new Reservation(id, orderId, status, items, null, createdAt, updatedAt);
    }

    /**
     * Rebuilds a persisted rejected reservation. Raises no events.
     *
     * @param id reservation id
     * @param orderId order id
     * @param items stored items
     * @param rejection stored reason and failing items
     * @param createdAt creation time
     * @param updatedAt last update time
     * @return the restored reservation
     */
    public static Reservation restoreRejected(
            ReservationId id,
            OrderId orderId,
            RequestedItems items,
            Rejection rejection,
            Instant createdAt,
            Instant updatedAt) {
        return new Reservation(
                id,
                orderId,
                ReservationStatus.REJECTED,
                items,
                Objects.requireNonNull(rejection, "rejection"),
                createdAt,
                updatedAt);
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
     * Why the reservation was rejected.
     *
     * @return the rejection, present only when the status is {@code REJECTED}
     */
    public Optional<Rejection> rejection() {
        return Optional.ofNullable(rejection);
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
