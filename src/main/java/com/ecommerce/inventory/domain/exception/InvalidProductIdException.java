package com.ecommerce.inventory.domain.exception;

/** Raised when a product id is missing, blank or too long. */
public class InvalidProductIdException extends InventoryDomainException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what is wrong with the product id
     */
    public InvalidProductIdException(String message) {
        super(message);
    }
}
