package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.domain.model.ReservationId;
import java.util.Optional;

/** Stores the outcome message of each reservation in the reservation transaction (FR-016, FR-017). */
public interface OutcomeMessageRepository {

    /**
     * Saves the outcome message of a reservation.
     *
     * @param reservationId reservation the message belongs to
     * @param message the message to publish
     */
    void save(ReservationId reservationId, OutboundMessage message);

    /**
     * Finds the stored outcome message of a reservation.
     *
     * @param reservationId reservation id
     * @return the stored message, if any
     */
    Optional<OutboundMessage> findByReservationId(ReservationId reservationId);
}
