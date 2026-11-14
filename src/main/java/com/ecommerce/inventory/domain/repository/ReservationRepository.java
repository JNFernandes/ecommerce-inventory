package com.ecommerce.inventory.domain.repository;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.Reservation;
import java.util.Optional;

/** Persistence port for reservations. */
public interface ReservationRepository {

    /**
     * Finds the reservation of an order.
     *
     * @param orderId order id
     * @return the reservation, if the order was already handled
     */
    Optional<Reservation> findByOrderId(OrderId orderId);

    /**
     * Saves a new reservation in the current transaction.
     *
     * @param reservation reservation to save
     */
    void save(Reservation reservation);

    /**
     * Saves the status, release time and update time of an existing reservation in the current
     * transaction.
     *
     * @param reservation reservation previously loaded and changed
     */
    void update(Reservation reservation);
}
