package com.ecommerce.inventory.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StockItemJpaRepository extends JpaRepository<StockItemEntity, String> {

    /** Write-locks the rows in ascending product id order (ADR 0002): one query, deterministic order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockItemEntity s where s.productId in :productIds order by s.productId asc")
    List<StockItemEntity> lockAllByProductIdIn(@Param("productIds") Collection<String> productIds);

    /**
     * Reads one catalog page without locks (US-04, research R3): product id in byte order
     * ({@code collate "C"}, independent of the database locale), then {@code limit}/{@code offset}.
     */
    @Query(
            value = "select * from stock_items order by product_id collate \"C\" limit :limit offset :offset",
            nativeQuery = true)
    List<StockItemEntity> findPageOrderedByProductId(@Param("limit") int limit, @Param("offset") int offset);
}
