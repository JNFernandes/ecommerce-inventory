package com.ecommerce.inventory.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Persistence model of {@code stock_items}. Never leaves this package. */
@Entity
@Table(name = "stock_items")
class StockItemEntity {

    @Id
    @Column(name = "product_id", length = 64)
    private String productId;

    /** Catalog name. Written only by migrations and the local seed; this class never changes it. */
    @Column(name = "name", nullable = false, length = 200, updatable = false)
    private String name;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItemEntity() {}

    String getProductId() {
        return productId;
    }

    String getName() {
        return name;
    }

    int getAvailableQuantity() {
        return availableQuantity;
    }

    void updateAvailableQuantity(int quantity, Instant at) {
        this.availableQuantity = quantity;
        this.updatedAt = at;
    }
}
