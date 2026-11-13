package com.ecommerce.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/** Persistence model of {@code reservation_items}. Never leaves this package. */
@Entity
@Table(name = "reservation_items")
class ReservationItemEntity {

    @EmbeddedId
    private ReservationItemId id;

    @MapsId("reservationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id")
    private ReservationEntity reservation;

    @Column(name = "requested_quantity", nullable = false)
    private long requestedQuantity;

    @Column(name = "failure_kind", length = 32)
    private String failureKind;

    @Column(name = "available_at_decision")
    private Integer availableAtDecision;

    protected ReservationItemEntity() {}

    ReservationItemEntity(
            ReservationEntity reservation,
            String productId,
            long requestedQuantity,
            String failureKind,
            Integer availableAtDecision) {
        this.id = new ReservationItemId(reservation.getId(), productId);
        this.reservation = reservation;
        this.requestedQuantity = requestedQuantity;
        this.failureKind = failureKind;
        this.availableAtDecision = availableAtDecision;
    }

    String getProductId() {
        return id.getProductId();
    }

    long getRequestedQuantity() {
        return requestedQuantity;
    }

    String getFailureKind() {
        return failureKind;
    }

    Integer getAvailableAtDecision() {
        return availableAtDecision;
    }
}
