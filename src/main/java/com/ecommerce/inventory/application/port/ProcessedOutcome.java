package com.ecommerce.inventory.application.port;

/** What processing an inbound event produced. */
public enum ProcessedOutcome {
    /** The event created a reservation (reserved or rejected) and an outcome message. */
    RESERVATION_DECIDED,
    /** The order already had a reservation; the event was ignored and nothing was published. */
    DUPLICATE_ORDER_IGNORED
}
