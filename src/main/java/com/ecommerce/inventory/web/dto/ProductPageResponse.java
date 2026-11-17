package com.ecommerce.inventory.web.dto;

import com.ecommerce.inventory.application.ProductPage;
import java.util.List;

/**
 * One page of the catalog as returned over HTTP.
 *
 * @param items products of this page, in product id order
 * @param total number of products in the catalog
 * @param limit maximum page size applied
 * @param offset number of products skipped
 */
public record ProductPageResponse(List<ProductResponse> items, long total, int limit, int offset) {

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
