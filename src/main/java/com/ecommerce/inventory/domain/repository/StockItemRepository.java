package com.ecommerce.inventory.domain.repository;

import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.StockItem;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Persistence port for stock items. */
public interface StockItemRepository {

    /**
     * Loads and write-locks the stock items of the given products, in ascending product id order,
     * until the surrounding transaction ends (ADR 0002). Unknown products are absent from the result.
     *
     * @param productIds products to lock
     * @return the locked stock items by product id
     */
    Map<ProductId, StockItem> lockAllOrderedByProductId(Set<ProductId> productIds);

    /**
     * Persists the available quantities of stock items previously loaded in the same transaction.
     *
     * @param stockItems stock items to save
     */
    void saveAll(Collection<StockItem> stockItems);
}
