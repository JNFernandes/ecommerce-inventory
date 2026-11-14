package com.ecommerce.inventory.domain.exception;

import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.ReservationId;

/**
 * A reserved product has no stock item when its reservation is released. Stock items are never
 * deleted, so this is a broken invariant: nothing is released, and the message goes through the normal
 * retry and dead-letter path instead of a partial release.
 */
public class StockItemMissingException extends InventoryDomainException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param reservationId reservation being released
     * @param productId product without a stock item
     */
    public StockItemMissingException(ReservationId reservationId, ProductId productId) {
        super("Cannot release reservation " + reservationId + ": product " + productId + " has no stock item");
    }
}
