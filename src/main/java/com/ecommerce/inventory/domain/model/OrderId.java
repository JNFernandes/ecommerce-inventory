package com.ecommerce.inventory.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an order, owned by the Orders service.
 *
 * @param value the order id
 */
public record OrderId(UUID value) {

    /** Validates the order id. */
    public OrderId {
        Objects.requireNonNull(value, "order id");
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
