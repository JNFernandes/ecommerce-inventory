package com.ecommerce.inventory.application;

import com.ecommerce.inventory.application.port.ProductCatalogReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only catalog queries (US-04, constitution Principle II). Every method runs in a read-only
 * transaction and takes no locks: it never waits for, or delays, reservations and releases. Results
 * are snapshots of saved stock; the authoritative availability check happens when an order is
 * reserved. No business rules: the catalog only reads.
 */
@Service
public class ProductQueryService {

    private final ProductCatalogReader catalog;

    /**
     * Creates the service.
     *
     * @param catalog read-only catalog access
     */
    public ProductQueryService(ProductCatalogReader catalog) {
        this.catalog = catalog;
    }

    /**
     * Lists one page of products in catalog order (product id, byte order). Items and total come from
     * one snapshot ({@code REPEATABLE_READ}, read-only); no locks are taken.
     *
     * @param limit maximum number of products, from 1 to {@link ProductPage#MAX_LIMIT}
     * @param offset number of products to skip, 0 or more
     * @return the page with the total number of products and the applied limit and offset
     * @throws IllegalArgumentException if the limit or offset is out of range
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductPage list(int limit, int offset) {
        if (limit < 1 || limit > ProductPage.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + ProductPage.MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        return new ProductPage(catalog.findPage(limit, offset), catalog.count(), limit, offset);
    }

    /**
     * Reads one product by its exact id (case-sensitive), read-only and without locks.
     *
     * @param productId product id as requested
     * @return the product
     * @throws ProductNotFoundException if no product has that id
     */
    @Transactional(readOnly = true)
    public ProductView get(String productId) {
        return catalog.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
