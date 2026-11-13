package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.RequestedItems;
import com.ecommerce.inventory.domain.model.Reservation;
import com.ecommerce.inventory.domain.model.ReservationId;

/** Maps the {@link Reservation} aggregate to and from {@code reservations} / {@code reservation_items}. */
final class ReservationMapper {

    private ReservationMapper() {}

    static ReservationEntity toEntity(Reservation reservation) {
        ReservationEntity entity = new ReservationEntity(
                reservation.id().value(),
                reservation.orderId().value(),
                reservation.status(),
                null,
                reservation.createdAt(),
                reservation.updatedAt());
        for (RequestedItem item : reservation.items().items()) {
            entity.addItem(item.productId().value(), item.quantity().value(), null, null);
        }
        return entity;
    }

    static Reservation toDomain(ReservationEntity entity) {
        RequestedItems items = new RequestedItems(entity.getItems().stream()
                .map(row ->
                        new RequestedItem(new ProductId(row.getProductId()), new Quantity(row.getRequestedQuantity())))
                .toList());
        return Reservation.restore(
                new ReservationId(entity.getId()),
                new OrderId(entity.getOrderId()),
                entity.getStatus(),
                items,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
