package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.exception.InvalidProductIdException;

/**
 * Identity of a product, such as {@code SKU-001}. Compared exactly (case-sensitive).
 *
 * @param value the product id text
 */
public record ProductId(String value) implements Comparable<ProductId> {

    /** Maximum length of a product id. */
    public static final int MAX_LENGTH = 64;

    /** Validates the product id. */
    public ProductId {
        if (value == null || value.isBlank()) {
            throw new InvalidProductIdException("Product id must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new InvalidProductIdException("Product id must be at most " + MAX_LENGTH + " characters");
        }
    }

    @Override
    public int compareTo(ProductId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
