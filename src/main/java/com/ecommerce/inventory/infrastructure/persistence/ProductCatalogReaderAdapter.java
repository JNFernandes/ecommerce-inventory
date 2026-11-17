package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.ProductView;
import com.ecommerce.inventory.application.port.ProductCatalogReader;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Reads the catalog from {@code stock_items} (US-04, research R3). Plain reads only: no lock mode, no
 * {@code lock_timeout}, no order lock, so a read never waits for or delays a reservation. The calling
 * query service owns the read-only transaction. Entities are mapped explicitly and never leave this
 * package.
 */
@Repository
public class ProductCatalogReaderAdapter implements ProductCatalogReader {

    private final StockItemJpaRepository jpaRepository;

    ProductCatalogReaderAdapter(StockItemJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<ProductView> findPage(int limit, int offset) {
        return jpaRepository.findPageOrderedByProductId(limit, offset).stream()
                .map(ProductCatalogReaderAdapter::toView)
                .toList();
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }

    @Override
    public Optional<ProductView> findById(String productId) {
        return jpaRepository.findById(productId).map(ProductCatalogReaderAdapter::toView);
    }

    private static ProductView toView(StockItemEntity row) {
        return new ProductView(row.getProductId(), row.getName(), row.getAvailableQuantity());
    }
}
