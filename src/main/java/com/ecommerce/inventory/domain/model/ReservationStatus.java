package com.ecommerce.inventory.domain.model;

/** Status of a reservation. Transitions: {@code RESERVED → RELEASED} only. */
public enum ReservationStatus {
    /** Every item of the order was reserved and stock was decreased. */
    RESERVED,
    /** At least one item could not be reserved; no stock was changed. Final. */
    REJECTED,
    /** The order was cancelled and the reserved stock was given back. Final. */
    RELEASED
}
