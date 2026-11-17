package com.ecommerce.inventory.web.dto;

import com.ecommerce.inventory.application.ProductView;

/**
 * One catalog product as returned over HTTP.
 *
 * @param id product id, as used in orders
 * @param name product name
 * @param availableQuantity units available when the request was served (a snapshot)
 */
public record ProductResponse(String id, String name, int availableQuantity) {

    /**
     * Maps the application read model to the response.
     *
     * @param view the product read model
     * @return the response record
     */
    public static ProductResponse from(ProductView view) {
        return new ProductResponse(view.productId(), view.name(), view.availableQuantity());
    }
}
