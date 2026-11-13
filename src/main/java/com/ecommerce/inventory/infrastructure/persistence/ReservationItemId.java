package com.ecommerce.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@code reservation_items}. */
@Embeddable
class ReservationItemId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "product_id", length = 64)
    private String productId;

    protected ReservationItemId() {}

    ReservationItemId(UUID reservationId, String productId) {
        this.reservationId = reservationId;
        this.productId = productId;
    }

    String getProductId() {
        return productId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReservationItemId that
                && Objects.equals(reservationId, that.reservationId)
                && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reservationId, productId);
    }
}
