package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.event.StockRejected;
import com.ecommerce.inventory.domain.event.StockReleased;
import com.ecommerce.inventory.domain.event.StockReserved;
import com.ecommerce.inventory.domain.exception.StockItemMissingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Aggregate: the outcome of handling one order. At most one reservation exists per order. {@link #decide}
 * creates it (US-01 FR-005 to FR-008); {@link #release} gives its stock back when the order is cancelled
 * (US-02 FR-005 to FR-008). Status moves only {@code RESERVED → RELEASED}; {@code REJECTED} and
 * {@code RELEASED} are final.
 */
public final class Reservation {

    private final ReservationId id;
    private final OrderId orderId;
    private ReservationStatus status;
    private final RequestedItems items;
    private final Rejection rejection;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant releasedAt;
    private final List<InventoryDomainEvent> domainEvents = new ArrayList<>();

    private Reservation(
            ReservationId id,
            OrderId orderId,
            ReservationStatus status,
            RequestedItems items,
            Rejection rejection,
            Instant createdAt,
            Instant updatedAt,
            Instant releasedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = Objects.requireNonNull(status, "status");
        this.items = Objects.requireNonNull(items, "items");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if ((status == ReservationStatus.REJECTED) != (rejection != null)) {
            throw new IllegalArgumentException("A reservation has a rejection if and only if it is REJECTED");
        }
        if ((status == ReservationStatus.RELEASED) != (releasedAt != null)) {
            throw new IllegalArgumentException("A reservation has a release time if and only if it is RELEASED");
        }
        this.rejection = rejection;
        this.releasedAt = releasedAt;
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
        Reservation reservation = new Reservation(id, orderId, ReservationStatus.RESERVED, items, null, now, now, null);
        reservation.domainEvents.add(new StockReserved(id, orderId, items.items(), now));
        return reservation;
    }

    private static Reservation rejected(
            ReservationId id, OrderId orderId, RequestedItems items, List<ItemFailure> failures, Instant now) {
        Set<FailureKind> kinds = EnumSet.noneOf(FailureKind.class);
        failures.forEach(failure -> kinds.add(failure.kind()));
        Rejection rejection = new Rejection(RejectionReason.from(kinds), failures);
        Reservation reservation =
                new Reservation(id, orderId, ReservationStatus.REJECTED, items, rejection, now, now, null);
        reservation.domainEvents.add(new StockRejected(id, orderId, rejection.reason(), rejection.failures(), now));
        return reservation;
    }

    /**
     * Gives the reserved stock back because the order was cancelled. Only a {@code RESERVED} reservation
     * releases anything: every reserved quantity is added back to its stock item, the status becomes
     * {@code RELEASED} and a {@link StockReleased} event is raised. A {@code REJECTED} reservation never
     * took stock and an already {@code RELEASED} one already gave it back; both are left unchanged, so
     * stock is never returned twice.
     *
     * @param lockedStock stock items of this reservation's products, locked for this decision
     * @param now release time
     * @return what the release did
     * @throws StockItemMissingException when a reserved product has no stock item; nothing is changed
     */
    public ReleaseOutcome release(Map<ProductId, StockItem> lockedStock, Instant now) {
        if (status == ReservationStatus.REJECTED) {
            return ReleaseOutcome.NOTHING_RESERVED;
        }
        if (status == ReservationStatus.RELEASED) {
            return ReleaseOutcome.ALREADY_RELEASED;
        }
        for (RequestedItem item : items.items()) {
            if (!lockedStock.containsKey(item.productId())) {
                throw new StockItemMissingException(id, item.productId());
            }
        }
        for (RequestedItem item : items.items()) {
            lockedStock.get(item.productId()).release(item.quantity());
        }
        status = ReservationStatus.RELEASED;
        releasedAt = now;
        updatedAt = now;
        domainEvents.add(new StockReleased(id, orderId, items.items(), now));
        return ReleaseOutcome.RELEASED;
    }

    /**
     * Rebuilds a persisted reserved reservation. Raises no events.
     *
     * @param id reservation id
     * @param orderId order id
     * @param status stored status; must be {@code RESERVED} (use {@link #restoreRejected} or
     *     {@link #restoreReleased} for the others)
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
        return new Reservation(id, orderId, status, items, null, createdAt, updatedAt, null);
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
                updatedAt,
                null);
    }

    /**
     * Rebuilds a persisted released reservation. Raises no events.
     *
     * @param id reservation id
     * @param orderId order id
     * @param items stored items
     * @param createdAt creation time
     * @param releasedAt release time, also the last update time
     * @return the restored reservation
     */
    public static Reservation restoreReleased(
            ReservationId id, OrderId orderId, RequestedItems items, Instant createdAt, Instant releasedAt) {
        return new Reservation(
                id,
                orderId,
                ReservationStatus.RELEASED,
                items,
                null,
                createdAt,
                releasedAt,
                Objects.requireNonNull(releasedAt, "releasedAt"));
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
     * When the reserved stock was given back.
     *
     * @return the release time, present only when the status is {@code RELEASED}
     */
    public Optional<Instant> releasedAt() {
        return Optional.ofNullable(releasedAt);
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
