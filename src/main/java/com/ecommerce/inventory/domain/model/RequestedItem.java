package com.ecommerce.inventory.domain.model;

import java.util.Objects;

/**
 * A product and the quantity an order requests.
 *
 * @param productId requested product
 * @param quantity requested quantity
 */
public record RequestedItem(ProductId productId, Quantity quantity) {

    /** Validates the item. */
    public RequestedItem {
        Objects.requireNonNull(productId, "productId");
        Objects.requireNonNull(quantity, "quantity");
    }
}
