package com.ecommerce.inventory.application;

/** No catalog product has the requested id (US-04 FR-009). The id is matched exactly. */
public class ProductNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The requested product id. */
    private final String productId;

    /**
     * Creates the exception.
     *
     * @param productId the requested product id
     */
    public ProductNotFoundException(String productId) {
        super("Product " + productId + " not found");
        this.productId = productId;
    }

    /**
     * The requested product id.
     *
     * @return the id exactly as requested
     */
    public String productId() {
        return productId;
    }
}
