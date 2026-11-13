package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.StockItem;

/** Maps {@link StockItemEntity} rows to the {@link StockItem} aggregate. */
final class StockItemMapper {

    private StockItemMapper() {}

    static StockItem toDomain(StockItemEntity row) {
        return new StockItem(new ProductId(row.getProductId()), row.getAvailableQuantity());
    }
}
