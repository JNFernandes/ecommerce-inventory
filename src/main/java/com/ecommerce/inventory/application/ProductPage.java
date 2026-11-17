package com.ecommerce.inventory.application;

import java.util.List;

/**
 * One page of the catalog (US-04): products in catalog order, the total number of products, and the
 * limit and offset that produced the page. Items and total come from one snapshot.
 *
 * @param items at most {@code limit} products, after skipping {@code offset} products
 * @param total number of products in the catalog
 * @param limit maximum page size applied, from 1 to {@link #MAX_LIMIT}
 * @param offset number of products skipped, 0 or more
 */
public record ProductPage(List<ProductView> items, long total, int limit, int offset) {

    /** Largest page a client may ask for. */
    public static final int MAX_LIMIT = 200;

    /**
     * Validates the page and copies its items.
     *
     * @throws IllegalArgumentException if a value is out of range or there are more items than the limit
     * @throws NullPointerException if {@code items} or one of them is null
     */
    public ProductPage {
        items = List.copyOf(items);
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (items.size() > limit) {
            throw new IllegalArgumentException("a page holds at most limit items");
        }
    }
}
