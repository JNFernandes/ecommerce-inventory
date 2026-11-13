package com.ecommerce.inventory.messaging;

/**
 * A consumed message cannot be parsed or fails validation. It is never retried: it goes straight to
 * the consumed-message dead-letter table (FR-003).
 */
public class InvalidMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message why the message is invalid
     */
    public InvalidMessageException(String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message why the message is invalid
     * @param cause the parsing failure
     */
    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
