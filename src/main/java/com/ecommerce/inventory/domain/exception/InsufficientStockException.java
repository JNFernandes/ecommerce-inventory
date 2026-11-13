package com.ecommerce.inventory.domain.exception;

/**
 * Raised when stock would go negative. A business rejection never raises it: the reservation decision
 * checks every item first, so this signals a broken invariant (constitution Principle IV).
 */
public class InsufficientStockException extends InventoryDomainException {

    private static final long serialVersionUID = 1L;

    private final String productId;
    private final long requested;
    private final int available;

    /**
     * Creates the exception.
     *
     * @param productId product whose stock is insufficient
     * @param requested quantity that was requested
     * @param available quantity that is available
     */
    public InsufficientStockException(String productId, long requested, int available) {
        super("Insufficient stock for " + productId + ": requested " + requested + ", available " + available);
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    /**
     * Product whose stock is insufficient.
     *
     * @return the product id
     */
    public String productId() {
        return productId;
    }

    /**
     * Quantity that was requested.
     *
     * @return the requested quantity
     */
    public long requested() {
        return requested;
    }

    /**
     * Quantity that is available.
     *
     * @return the available quantity
     */
    public int available() {
        return available;
    }
}
