package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.exception.InvalidQuantityException;

/**
 * A strictly positive number of units. Stored as {@code long} so consolidating several {@code int}
 * order lines can never overflow; an over-large total is simply insufficient stock.
 *
 * @param value number of units
 */
public record Quantity(long value) {

    /** Validates the quantity. */
    public Quantity {
        if (value <= 0) {
            throw new InvalidQuantityException("Quantity must be greater than 0, was " + value);
        }
    }

    /**
     * Adds two quantities.
     *
     * @param other quantity to add
     * @return the sum
     */
    public Quantity plus(Quantity other) {
        return new Quantity(Math.addExact(value, other.value));
    }
}
