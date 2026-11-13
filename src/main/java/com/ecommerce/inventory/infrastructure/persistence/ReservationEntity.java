package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.ReservationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persistence model of {@code reservations}. Never leaves this package. */
@Entity
@Table(name = "reservations")
class ReservationEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "rejection_reason", length = 64)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("id.productId asc")
    private List<ReservationItemEntity> items = new ArrayList<>();

    protected ReservationEntity() {}

    ReservationEntity(
            UUID id,
            UUID orderId,
            ReservationStatus status,
            String rejectionReason,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.orderId = orderId;
        this.status = status;
        this.rejectionReason = rejectionReason;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void addItem(String productId, long requestedQuantity, String failureKind, Integer availableAtDecision) {
        items.add(new ReservationItemEntity(this, productId, requestedQuantity, failureKind, availableAtDecision));
    }

    UUID getId() {
        return id;
    }

    UUID getOrderId() {
        return orderId;
    }

    ReservationStatus getStatus() {
        return status;
    }

    String getRejectionReason() {
        return rejectionReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    List<ReservationItemEntity> getItems() {
        return List.copyOf(items);
    }
}
