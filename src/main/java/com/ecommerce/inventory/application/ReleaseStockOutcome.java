package com.ecommerce.inventory.application;

import com.ecommerce.inventory.domain.model.ReservationId;
import java.util.Optional;

/** What handling a {@link ReleaseStockCommand} produced. */
public sealed interface ReleaseStockOutcome {

    /**
     * The reserved stock was given back and stock-released was published.
     *
     * @param reservationId the released reservation
     */
    record Released(ReservationId reservationId) implements ReleaseStockOutcome {}

    /**
     * Nothing to give back; nothing changed and nothing was published (US-02 FR-006, FR-007).
     *
     * @param reservationId the order's reservation, if it has one
     * @param reason why the cancellation was ignored
     */
    record Ignored(Optional<ReservationId> reservationId, String reason) implements ReleaseStockOutcome {}

    /** The cancellation arrived before the placement; a cancellation marker was stored (FR-010). */
    record CancellationRecorded() implements ReleaseStockOutcome {}

    /**
     * The event was already processed and had released stock: nothing changed and the stored
     * stock-released message was republished with its original event id (FR-015).
     *
     * @param reservationId the released reservation
     */
    record Redelivered(ReservationId reservationId) implements ReleaseStockOutcome {}
}
