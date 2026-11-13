package com.ecommerce.inventory.domain.exception;

/** Base type of every exception raised by the Inventory domain when an invariant would be broken. */
public abstract class InventoryDomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description of the broken invariant
     */
    protected InventoryDomainException(String message) {
        super(message);
    }
}
