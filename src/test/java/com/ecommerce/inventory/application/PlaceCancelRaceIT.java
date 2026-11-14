package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.StockFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the race rule on a real PostgreSQL (US-02 FR-012, SC-003, SC-004, ADR 0005): whatever the
 * timing of placement and cancellation, a cancelled order never ends with stock held for it, and
 * releases never lose updates against concurrent reservations of the same product.
 */
class PlaceCancelRaceIT extends AbstractPostgresIT {

    private static final String PLACED = "orders.order-placed";
    private static final String CANCELLED = "orders.order-cancelled";

    @Autowired
    private ReserveStockService reserveService;

    @Autowired
    private ReleaseStockService releaseService;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OutboundMessagePublisher publisher;

    private StockFixtures stock;
    private ExecutorService pool;

    @BeforeEach
    void arrange() {
        DatabaseCleaner.truncateAll(jdbc);
        stock = new StockFixtures(jdbc);
        pool = Executors.newFixedThreadPool(32);
    }

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("should never leave a cancelled order RESERVED, whatever the timing of placement and cancellation")
    void shouldNeverLeaveCancelledOrderReserved() throws Exception {
        stock.given("SKU-001", 100);

        for (int round = 0; round < 50; round++) {
            OrderId order = new OrderId(UUID.randomUUID());
            runTogether(List.of(() -> place(order), () -> cancel(order)));
            assertNotHeld(order);
        }
        for (int round = 0; round < 10; round++) {
            OrderId cancelledFirst = new OrderId(UUID.randomUUID());
            cancel(cancelledFirst);
            place(cancelledFirst);
            assertNotHeld(cancelledFirst);

            OrderId placedFirst = new OrderId(UUID.randomUUID());
            place(placedFirst);
            cancel(placedFirst);
            assertNotHeld(placedFirst);
        }

        assertThat(stock.available("SKU-001")).contains(100);
    }

    @Test
    @DisplayName("should not lose updates when releases race other orders' reservations of the same product")
    void shouldNotLoseUpdatesBetweenReleasesAndReservations() throws Exception {
        stock.given("SKU-002", 100);
        List<OrderId> existing = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            OrderId order = new OrderId(UUID.randomUUID());
            reserveService.reserve(placement(order, "SKU-002", 2));
            existing.add(order);
        }
        assertThat(stock.available("SKU-002")).contains(60);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (OrderId order : existing.subList(0, 10)) {
            tasks.add(() -> releaseService.release(new ReleaseStockCommand(UUID.randomUUID(), CANCELLED, order)));
        }
        for (int i = 0; i < 20; i++) {
            OrderId order = new OrderId(UUID.randomUUID());
            tasks.add(() -> reserveService.reserve(placement(order, "SKU-002", 2)));
        }
        runTogether(tasks);

        Integer stillReserved =
                jdbc.queryForObject("select count(*) from reservations where status = 'RESERVED'", Integer.class);
        assertThat(stillReserved).isEqualTo(30);
        assertThat(stock.available("SKU-002")).contains(100 - 2 * stillReserved);
        assertThat(jdbc.queryForObject("select min(available_quantity) from stock_items", Integer.class))
                .isNotNegative();
    }

    private Object place(OrderId order) {
        return reserveService.reserve(placement(order, "SKU-001", 1));
    }

    private Object cancel(OrderId order) {
        return releaseService.release(new ReleaseStockCommand(UUID.randomUUID(), CANCELLED, order));
    }

    private static ReserveStockCommand placement(OrderId order, String productId, long quantity) {
        return new ReserveStockCommand(
                UUID.randomUUID(),
                PLACED,
                order,
                List.of(new RequestedItem(new ProductId(productId), new Quantity(quantity))));
    }

    /** Either the cancellation came first (marker, no reservation) or the reservation was released. */
    private void assertNotHeld(OrderId order) {
        List<String> statuses =
                jdbc.queryForList("select status from reservations where order_id = ?", String.class, order.value());
        if (statuses.isEmpty()) {
            assertThat(jdbc.queryForObject(
                            "select count(*) from cancellation_markers where order_id = ?",
                            Integer.class,
                            order.value()))
                    .as("order %s has neither a reservation nor a marker", order)
                    .isEqualTo(1);
        } else {
            assertThat(statuses).as("order %s", order).containsExactly("RELEASED");
        }
    }

    /** Starts every task at the same moment; fails on any exception or on a task that does not finish. */
    private void runTogether(List<Callable<Object>> tasks) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        for (Future<Object> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw new AssertionError("A concurrent task failed", e.getCause());
            } catch (TimeoutException e) {
                throw new AssertionError("A concurrent task did not finish within 30 s (deadlock?)", e);
            }
        }
    }
}
