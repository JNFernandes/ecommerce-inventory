package com.ecommerce.inventory.web.dto;

import com.ecommerce.inventory.application.ProductPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * One page of the catalog as returned over HTTP.
 *
 * @param items products of this page, in product id order
 * @param total number of products in the catalog
 * @param limit maximum page size applied
 * @param offset number of products skipped
 */
@Schema(description = "One page of the catalog. items and total come from one snapshot.")
public record ProductPageResponse(
        @Schema(description = "At most limit products after skipping offset, in product id order; empty past the end.")
        List<ProductResponse> items,

        @Schema(description = "Number of products in the catalog.", example = "5")
        long total,

        @Schema(description = "Maximum page size applied (default 50, at most 200).", example = "50")
        int limit,

        @Schema(description = "Number of products skipped (default 0).", example = "0")
        int offset) {

    /**
     * Copies the items so the response cannot change after it is built.
     *
     * @throws NullPointerException if {@code items} or one of them is null
     */
    public ProductPageResponse {
        items = List.copyOf(items);
    }

    /**
     * Maps the application page to the response.
     *
     * @param page the page read model
     * @return the response record
     */
    public static ProductPageResponse from(ProductPage page) {
        return new ProductPageResponse(
                page.items().stream().map(ProductResponse::from).toList(), page.total(), page.limit(), page.offset());
    }
}
