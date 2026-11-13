package com.ecommerce.inventory.application;

import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.domain.model.ReservationStatus;

/** What handling a {@link ReserveStockCommand} produced. */
public sealed interface ReserveStockOutcome {

    /**
     * A new reservation was decided, saved and its outcome published.
     *
     * @param reservationId the new reservation
     * @param status reserved or rejected
     */
    record Decided(ReservationId reservationId, ReservationStatus status) implements ReserveStockOutcome {}

    /**
     * The event was already processed: nothing changed and the stored outcome was republished with its
     * original event id (FR-016).
     *
     * @param reservationId the reservation the event had created
     */
    record Redelivered(ReservationId reservationId) implements ReserveStockOutcome {}

    /**
     * The order already had a reservation: the event was recorded and ignored, nothing was published
     * (FR-014).
     *
     * @param existingReservationId the reservation the order already has
     */
    record DuplicateOrderIgnored(ReservationId existingReservationId) implements ReserveStockOutcome {}
}
