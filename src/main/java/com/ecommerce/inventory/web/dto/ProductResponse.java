package com.ecommerce.inventory.web.dto;

import com.ecommerce.inventory.application.ProductView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One catalog product as returned over HTTP.
 *
 * @param id product id, as used in orders
 * @param name product name
 * @param availableQuantity units available when the request was served (a snapshot)
 */
@Schema(description = "A catalog product with its available stock.")
public record ProductResponse(
        @Schema(description = "Product id, as used in orders. Matched exactly, case-sensitive.", example = "SKU-001")
        String id,

        @Schema(description = "Product name.", example = "Wireless Mouse")
        String name,

        @Schema(
                description = "Units available when the request was served: saved stock after reservations,"
                        + " read without locks. A snapshot; the order reservation is the authoritative check.",
                minimum = "0",
                example = "10")
        int availableQuantity) {

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
