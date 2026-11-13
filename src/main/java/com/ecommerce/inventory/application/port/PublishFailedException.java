package com.ecommerce.inventory.application.port;

/** The broker did not acknowledge a message. The committed database state stays as it is. */
public class PublishFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause underlying failure
     */
    public PublishFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
