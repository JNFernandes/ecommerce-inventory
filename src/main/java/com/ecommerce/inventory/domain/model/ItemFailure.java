package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import java.util.Objects;

/**
 * An item that could not be reserved, with the quantities seen at decision time (FR-007).
 *
 * @param productId product that failed
 * @param requested consolidated quantity requested
 * @param available quantity available at decision time; 0 for an unknown product
 * @param kind why the item failed
 */
public record ItemFailure(ProductId productId, long requested, int available, FailureKind kind) {

    /** Validates the failure. */
    public ItemFailure {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(kind, "kind");
        if (requested <= 0) {
            throw new InvalidQuantityException("Requested quantity must be greater than 0, was " + requested);
        }
        if (available < 0) {
            throw new InvalidQuantityException("Available quantity must not be negative, was " + available);
        }
        if (kind == FailureKind.UNKNOWN_PRODUCT && available != 0) {
            throw new IllegalArgumentException("An unknown product has 0 available, was " + available);
        }
    }
}
