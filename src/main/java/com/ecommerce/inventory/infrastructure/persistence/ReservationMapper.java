package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.FailureKind;
import com.ecommerce.inventory.domain.model.ItemFailure;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.Rejection;
import com.ecommerce.inventory.domain.model.RejectionReason;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.RequestedItems;
import com.ecommerce.inventory.domain.model.Reservation;
import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.domain.model.ReservationStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the {@link Reservation} aggregate to and from {@code reservations} / {@code reservation_items}.
 * The rejection reason is stored as the enum name; failing items carry {@code failure_kind} and
 * {@code available_at_decision}, other items leave both null.
 */
final class ReservationMapper {

    private ReservationMapper() {}

    static ReservationEntity toEntity(Reservation reservation) {
        ReservationEntity entity = new ReservationEntity(
                reservation.id().value(),
                reservation.orderId().value(),
                reservation.status(),
                reservation
                        .rejection()
                        .map(rejection -> rejection.reason().name())
                        .orElse(null),
                reservation.createdAt(),
                reservation.updatedAt());
        Map<ProductId, ItemFailure> failures = new HashMap<>();
        reservation
                .rejection()
                .ifPresent(rejection ->
                        rejection.failures().forEach(failure -> failures.put(failure.productId(), failure)));
        for (RequestedItem item : reservation.items().items()) {
            ItemFailure failure = failures.get(item.productId());
            entity.addItem(
                    item.productId().value(),
                    item.quantity().value(),
                    failure == null ? null : failure.kind().name(),
                    failure == null ? null : failure.available());
        }
        applyState(entity, reservation);
        return entity;
    }

    /** Copies the mutable state (status, release time, update time) onto an entity. */
    static void applyState(ReservationEntity entity, Reservation reservation) {
        entity.applyState(reservation.status(), reservation.releasedAt().orElse(null), reservation.updatedAt());
    }

    static Reservation toDomain(ReservationEntity entity) {
        List<ReservationItemEntity> rows = entity.getItems();
        RequestedItems items = new RequestedItems(rows.stream()
                .map(row ->
                        new RequestedItem(new ProductId(row.getProductId()), new Quantity(row.getRequestedQuantity())))
                .toList());
        ReservationId id = new ReservationId(entity.getId());
        OrderId orderId = new OrderId(entity.getOrderId());
        if (entity.getStatus() == ReservationStatus.RELEASED) {
            return Reservation.restoreReleased(id, orderId, items, entity.getCreatedAt(), entity.getReleasedAt());
        }
        if (entity.getStatus() == ReservationStatus.RESERVED) {
            return Reservation.restore(
                    id, orderId, entity.getStatus(), items, entity.getCreatedAt(), entity.getUpdatedAt());
        }
        List<ItemFailure> failures = rows.stream()
                .filter(row -> row.getFailureKind() != null)
                .map(row -> new ItemFailure(
                        new ProductId(row.getProductId()),
                        row.getRequestedQuantity(),
                        row.getAvailableAtDecision() == null ? 0 : row.getAvailableAtDecision(),
                        FailureKind.valueOf(row.getFailureKind())))
                .toList();
        Rejection rejection = new Rejection(RejectionReason.valueOf(entity.getRejectionReason()), failures);
        return Reservation.restoreRejected(id, orderId, items, rejection, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
