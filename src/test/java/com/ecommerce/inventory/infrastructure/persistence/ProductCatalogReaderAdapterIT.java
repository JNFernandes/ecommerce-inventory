package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ecommerce.inventory.application.ProductView;
import com.ecommerce.inventory.application.port.ProductCatalogReader;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.StockFixtures;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The catalog read adapter on a real PostgreSQL (US-04 FR-003, FR-005, FR-008, FR-015). */
class ProductCatalogReaderAdapterIT extends AbstractPostgresIT {

    @Autowired
    private ProductCatalogReader catalog;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private StockFixtures stock;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
        stock = new StockFixtures(jdbc);
    }

    @Test
    @DisplayName("should list products in product id byte order")
    void shouldListInByteOrder() {
        stock.given("sku-001", 1);
        stock.given("SKU-010", 1);
        stock.given("SKU-002", 1);
        stock.given("SKU-001", 1);

        assertThat(catalog.findPage(50, 0))
                .extracting(ProductView::productId)
                .containsExactly("SKU-001", "SKU-002", "SKU-010", "sku-001");
    }

    @Test
    @DisplayName("should return the requested slice")
    void shouldReturnSlice() {
        givenFiveProducts();

        assertThat(catalog.findPage(2, 3)).extracting(ProductView::productId).containsExactly("SKU-004", "SKU-005");
        assertThat(catalog.findPage(2, 0)).extracting(ProductView::productId).containsExactly("SKU-001", "SKU-002");
    }

    @Test
    @DisplayName("should return an empty page past the end")
    void shouldReturnEmptyPagePastTheEnd() {
        givenFiveProducts();

        assertThat(catalog.findPage(50, 10)).isEmpty();
        assertThat(catalog.findPage(50, 5)).isEmpty();
    }

    @Test
    @DisplayName("should count every product")
    void shouldCountProducts() {
        assertThat(catalog.count()).isZero();

        givenFiveProducts();

        assertThat(catalog.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("should find a product by its exact id")
    void shouldFindByExactId() {
        stock.given("SKU-003", "USB-C Hub", 100);

        assertThat(catalog.findById("SKU-003")).contains(new ProductView("SKU-003", "USB-C Hub", 100));
        assertThat(catalog.findById("sku-003")).isEmpty();
        assertThat(catalog.findById("SKU-999")).isEmpty();
    }

    @Test
    @DisplayName("should map name and available quantity")
    void shouldMapNameAndQuantity() {
        stock.given("SKU-005", "Webcam HD", 0);

        assertThat(catalog.findPage(50, 0)).containsExactly(new ProductView("SKU-005", "Webcam HD", 0));
    }

    @Test
    @DisplayName("should read stock while another transaction holds the row lock")
    void shouldReadWhileRowIsLocked() throws Exception {
        stock.given("SKU-001", "Wireless Mouse", 10);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForList("select * from stock_items where product_id = 'SKU-001' for update");
            locked.countDown();
            await(release);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                assertThat(catalog.findById("SKU-001")).isPresent();
                assertThat(catalog.findPage(50, 0)).hasSize(1);
                assertThat(catalog.count()).isEqualTo(1);
            });
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    private void givenFiveProducts() {
        for (int i = 5; i >= 1; i--) {
            stock.given("SKU-00" + i, i);
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
