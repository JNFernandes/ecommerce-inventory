package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.domain.model.OrderId;

/**
 * Serialises all work on one order (placement and cancellation) until the current transaction ends
 * (US-02 FR-012, ADR 0005). Must be called inside a transaction, before reading anything about the
 * order and before locking stock rows.
 */
public interface OrderLock {

    /**
     * Blocks until no other transaction holds the lock for this order, then holds it until the current
     * transaction commits or rolls back. The wait is bounded by the configured lock timeout.
     *
     * @param orderId the order to lock
     */
    void lock(OrderId orderId);
}
