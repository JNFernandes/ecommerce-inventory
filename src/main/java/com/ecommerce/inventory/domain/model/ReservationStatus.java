package com.ecommerce.inventory.domain.model;

/** Status of a reservation. {@code RELEASED} is added by US-02. */
public enum ReservationStatus {
    /** Every item of the order was reserved and stock was decreased. */
    RESERVED,
    /** At least one item could not be reserved; no stock was changed. */
    REJECTED
}
