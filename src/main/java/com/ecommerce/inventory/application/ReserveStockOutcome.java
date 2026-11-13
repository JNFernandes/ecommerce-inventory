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
}
