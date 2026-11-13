package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.StockItem;
import com.ecommerce.inventory.domain.repository.StockItemRepository;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.StockFixtures;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(properties = "inventory.stock.lock-timeout=300ms")
class StockItemRepositoryAdapterIT extends AbstractPostgresIT {

    private static final ProductId SKU_1 = new ProductId("SKU-001");
    private static final ProductId SKU_2 = new ProductId("SKU-002");

    @Autowired
    private StockItemRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private StockFixtures stock;

    @BeforeEach
    void arrange() {
        DatabaseCleaner.truncateAll(jdbc);
        stock = new StockFixtures(jdbc);
        stock.given("SKU-002", 25);
        stock.given("SKU-001", 10);
    }

    @Test
    @DisplayName("should return only existing products, ordered by product id")
    void shouldReturnExistingProductsOrdered() {
        Map<ProductId, StockItem> locked = transactions.execute(
                status -> repository.lockAllOrderedByProductId(Set.of(SKU_2, new ProductId("SKU-999"), SKU_1)));

        assertThat(locked).containsOnlyKeys(SKU_1, SKU_2);
        assertThat(locked.keySet()).containsExactly(SKU_1, SKU_2);
        assertThat(locked.get(SKU_1).availableQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("should persist decreased quantities of locked stock items")
    void shouldPersistDecreasedQuantities() {
        transactions.executeWithoutResult(status -> {
            Map<ProductId, StockItem> locked = repository.lockAllOrderedByProductId(Set.of(SKU_1));
            locked.get(SKU_1).reserve(new Quantity(3));
            repository.saveAll(locked.values());
        });

        assertThat(stock.available("SKU-001")).contains(7);
    }

    @Test
    @DisplayName("should hold the row lock until commit and time out a competing transaction")
    void shouldTimeOutWhileAnotherTransactionHoldsTheLock() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            repository.lockAllOrderedByProductId(Set.of(SKU_1));
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(
                            status -> repository.lockAllOrderedByProductId(Set.of(SKU_1))))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Latch was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
