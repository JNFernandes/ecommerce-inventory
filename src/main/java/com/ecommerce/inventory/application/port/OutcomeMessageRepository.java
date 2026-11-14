package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.domain.model.ReservationId;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the outcome messages of reservations (reserve and release) in the transaction that produced
 * them, so they can be republished verbatim on redelivery (ADR 0004).
 */
public interface OutcomeMessageRepository {

    /**
     * Saves an outcome message of a reservation.
     *
     * @param reservationId reservation the message belongs to
     * @param message the message to publish
     */
    void save(ReservationId reservationId, OutboundMessage message);

    /**
     * Finds a stored outcome message by its event id.
     *
     * @param eventId event id of the message
     * @return the stored message, if any
     */
    Optional<OutboundMessage> findByEventId(UUID eventId);
}
