package com.ecommerce.inventory.domain.exception;

/** Raised when a quantity or an item list is not acceptable (zero, negative or empty). */
public class InvalidQuantityException extends InventoryDomainException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the quantity
     */
    public InvalidQuantityException(String message) {
        super(message);
    }
}
