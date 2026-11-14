package com.ecommerce.inventory.domain.model;

import com.ecommerce.inventory.domain.exception.InsufficientStockException;
import com.ecommerce.inventory.domain.exception.InvalidQuantityException;
import java.util.Objects;

/** Aggregate: a product Inventory can supply and its available quantity, which is never negative. */
public final class StockItem {

    private final ProductId productId;
    private int availableQuantity;

    /**
     * Creates or restores a stock item.
     *
     * @param productId product identity
     * @param availableQuantity available units, at least 0
     */
    public StockItem(ProductId productId, int availableQuantity) {
        this.productId = Objects.requireNonNull(productId, "productId");
        if (availableQuantity < 0) {
            throw new InvalidQuantityException("Available quantity must not be negative, was " + availableQuantity);
        }
        this.availableQuantity = availableQuantity;
    }

    /**
     * Whether the requested quantity can be taken from the available stock.
     *
     * @param quantity requested quantity
     * @return {@code true} when at least that many units are available
     */
    public boolean canSupply(Quantity quantity) {
        return availableQuantity >= quantity.value();
    }

    /**
     * Takes the quantity out of the available stock.
     *
     * @param quantity quantity to reserve
     * @throws InsufficientStockException when stock would go negative; stock is left unchanged
     */
    public void reserve(Quantity quantity) {
        if (!canSupply(quantity)) {
            throw new InsufficientStockException(productId.value(), quantity.value(), availableQuantity);
        }
        availableQuantity -= Math.toIntExact(quantity.value());
    }

    /**
     * Gives a previously reserved quantity back to the available stock.
     *
     * @param quantity quantity to give back
     * @throws InvalidQuantityException when the result would not fit the stock quantity range
     */
    public void release(Quantity quantity) {
        long released = availableQuantity + quantity.value();
        if (released > Integer.MAX_VALUE) {
            throw new InvalidQuantityException("Releasing " + quantity.value() + " units of " + productId
                    + " would exceed the maximum stock quantity");
        }
        availableQuantity = (int) released;
    }

    /**
     * Product identity.
     *
     * @return the product id
     */
    public ProductId productId() {
        return productId;
    }

    /**
     * Units currently available.
     *
     * @return the available quantity
     */
    public int availableQuantity() {
        return availableQuantity;
    }
}
