package com.ecommerce.inventory.application.port;

/** What processing an inbound event produced. */
public enum ProcessedOutcome {
    /** A placement created a reservation (reserved or rejected) and an outcome message. */
    RESERVATION_DECIDED(true),
    /** A placement for an order that already had a reservation; ignored, nothing published. */
    DUPLICATE_ORDER_IGNORED(false),
    /** A cancellation released a reserved reservation and produced a stock-released message. */
    STOCK_RELEASED(true),
    /** A cancellation with nothing to give back (rejected, already released, or already recorded). */
    CANCELLATION_IGNORED(false),
    /** A cancellation that arrived before the placement; a cancellation marker was stored. */
    CANCELLATION_RECORDED(false),
    /** A placement of an order that was already cancelled; nothing reserved, nothing published. */
    PLACEMENT_CANCELLED(false);

    private final boolean publishesMessage;

    ProcessedOutcome(boolean publishesMessage) {
        this.publishesMessage = publishesMessage;
    }

    /**
     * Whether this outcome produced a message, which is republished when the event is redelivered.
     *
     * @return {@code true} for outcomes with a stored outcome message
     */
    public boolean publishesMessage() {
        return publishesMessage;
    }
}
