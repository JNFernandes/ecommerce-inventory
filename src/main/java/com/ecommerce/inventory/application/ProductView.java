package com.ecommerce.inventory.application;

/**
 * Read model of one catalog product (US-04): what a client sees. A snapshot of saved stock, read
 * without locks; it carries no domain or persistence type.
 *
 * @param productId product id used in orders, never blank
 * @param name product name, never blank
 * @param availableQuantity units available right now, never negative
 */
public record ProductView(String productId, String name, int availableQuantity) {

    /**
     * Validates the view.
     *
     * @throws IllegalArgumentException if the id or name is null or blank, or the quantity is negative
     */
    public ProductView {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must not be negative");
        }
    }
}
