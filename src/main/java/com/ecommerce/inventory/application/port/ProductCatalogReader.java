package com.ecommerce.inventory.application.port;

import com.ecommerce.inventory.application.ProductView;
import java.util.List;
import java.util.Optional;

/**
 * Read-only access to the product catalog (US-04). Every method is a plain read: it takes no locks
 * and never waits for, or delays, reservations and releases. Catalog order is the product id in byte
 * order, independent of the database locale.
 */
public interface ProductCatalogReader {

    /**
     * Reads one page of products in catalog order. Read-only, takes no locks.
     *
     * @param limit maximum number of products, 1 or more
     * @param offset number of products to skip, 0 or more
     * @return at most {@code limit} products; empty past the end
     */
    List<ProductView> findPage(int limit, int offset);

    /**
     * Counts the products. Read-only, takes no locks.
     *
     * @return the number of products in the catalog
     */
    long count();

    /**
     * Reads one product by its exact id (case-sensitive). Read-only, takes no locks.
     *
     * @param productId product id
     * @return the product, or empty when no product has that id
     */
    Optional<ProductView> findById(String productId);
}
