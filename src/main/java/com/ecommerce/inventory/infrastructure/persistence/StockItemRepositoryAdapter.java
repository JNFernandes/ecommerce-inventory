package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.StockItem;
import com.ecommerce.inventory.domain.repository.StockItemRepository;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Repository;

/**
 * Stock persistence with pessimistic row locks taken in ascending product id order (ADR 0002). The
 * lock wait is bounded with a transaction-local {@code lock_timeout} (research R2).
 */
@Repository
public class StockItemRepositoryAdapter implements StockItemRepository {

    private final StockItemJpaRepository jpaRepository;
    private final EntityManager entityManager;
    private final Duration lockTimeout;
    private final Clock clock;

    StockItemRepositoryAdapter(
            StockItemJpaRepository jpaRepository,
            EntityManager entityManager,
            InventoryProperties properties,
            Clock clock) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
        this.lockTimeout = properties.stock().lockTimeout();
        this.clock = clock;
    }

    @Override
    public Map<ProductId, StockItem> lockAllOrderedByProductId(Set<ProductId> productIds) {
        entityManager
                .createNativeQuery("select set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeout.toMillis() + "ms")
                .getSingleResult();
        List<String> ids = productIds.stream().map(ProductId::value).toList();
        Map<ProductId, StockItem> locked = new LinkedHashMap<>();
        for (StockItemEntity row : jpaRepository.lockAllByProductIdIn(ids)) {
            locked.put(new ProductId(row.getProductId()), StockItemMapper.toDomain(row));
        }
        return locked;
    }

    @Override
    public void saveAll(Collection<StockItem> stockItems) {
        for (StockItem stockItem : stockItems) {
            StockItemEntity row = entityManager.find(
                    StockItemEntity.class, stockItem.productId().value());
            if (row == null) {
                throw new IllegalStateException("Stock item " + stockItem.productId() + " was not loaded");
            }
            row.updateAvailableQuantity(stockItem.availableQuantity(), clock.instant());
        }
    }
}
