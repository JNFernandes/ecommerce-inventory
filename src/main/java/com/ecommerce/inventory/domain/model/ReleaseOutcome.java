package com.ecommerce.inventory.domain.model;

/** What {@link Reservation#release} did. */
public enum ReleaseOutcome {
    /** The reserved stock was given back and the reservation is now {@code RELEASED}. */
    RELEASED,
    /** The reservation was {@code REJECTED}: no stock was ever taken, nothing to give back. */
    NOTHING_RESERVED,
    /** The reservation was already {@code RELEASED}: stock is never given back twice. */
    ALREADY_RELEASED
}
