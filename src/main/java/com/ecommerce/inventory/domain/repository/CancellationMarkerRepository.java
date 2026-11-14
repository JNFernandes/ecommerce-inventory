package com.ecommerce.inventory.domain.repository;

import com.ecommerce.inventory.domain.model.CancellationMarker;
import com.ecommerce.inventory.domain.model.OrderId;

/** Persistence port for cancellation markers. */
public interface CancellationMarkerRepository {

    /**
     * Whether the order was recorded as cancelled before its placement.
     *
     * @param orderId order id
     * @return {@code true} when a marker exists
     */
    boolean exists(OrderId orderId);

    /**
     * Saves a new marker in the current transaction.
     *
     * @param marker the marker
     */
    void save(CancellationMarker marker);
}
