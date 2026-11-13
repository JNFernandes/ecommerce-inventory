package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.ReservationStatus;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves the concurrency guarantees of ADR 0002 on a real PostgreSQL: no overselling, no deadlock,
 * and never two reservations for one order (spec Story 3, FR-010, FR-011, FR-013, SC-002).
 */
class ConcurrentReservationIT extends AbstractPostgresIT {

    private static final String TOPIC = "orders.order-placed";

    @Autowired
    private ReserveStockService service;

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
        pool = Executors.newFixedThreadPool(20);
    }

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    @RepeatedTest(3)
    @DisplayName("should reserve exactly 5 of 20 concurrent single-unit orders for a 5-unit product (SC-002)")
    void shouldNeverOversell() throws Exception {
        stock.given("SKU-004", 5);
        List<Callable<ReserveStockOutcome>> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orders.add(() -> service.reserve(command(UUID.randomUUID(), item("SKU-004", 1))));
        }

        List<ReserveStockOutcome> outcomes = runTogether(orders);

        assertThat(statuses(outcomes, ReservationStatus.RESERVED)).isEqualTo(5);
        assertThat(statuses(outcomes, ReservationStatus.REJECTED)).isEqualTo(15);
        assertThat(stock.available("SKU-004")).contains(0);
        assertThat(jdbc.queryForObject(
                        "select count(*) from reservations where status = 'REJECTED' and rejection_reason = ?",
                        Integer.class,
                        "INSUFFICIENT_STOCK"))
                .isEqualTo(15);
        verify(publisher, times(20)).publish(any());
    }

    @Test
    @DisplayName("should complete orders that list the same products in opposite order without deadlock")
    void shouldNotDeadlockOnOppositeItemOrder() throws Exception {
        stock.given("SKU-001", 10_000);
        stock.given("SKU-002", 10_000);

        for (int round = 0; round < 50; round++) {
            List<ReserveStockOutcome> outcomes = runTogether(List.of(
                    () -> service.reserve(command(UUID.randomUUID(), item("SKU-001", 1), item("SKU-002", 1))),
                    () -> service.reserve(command(UUID.randomUUID(), item("SKU-002", 1), item("SKU-001", 1)))));
            assertThat(statuses(outcomes, ReservationStatus.RESERVED)).isEqualTo(2);
        }

        assertThat(stock.available("SKU-001")).contains(10_000 - 100);
        assertThat(stock.available("SKU-002")).contains(10_000 - 100);
        assertThat(jdbc.queryForObject("select min(available_quantity) from stock_items", Integer.class))
                .isNotNegative();
    }

    @RepeatedTest(5)
    @DisplayName("should create one reservation and take stock once for two concurrent events of the same order")
    void shouldKeepOneReservationPerOrder() throws Exception {
        stock.given("SKU-001", 10);
        UUID orderId = UUID.randomUUID();
        clearInvocations(publisher);

        List<Object> results = runTogetherCapturingFailures(List.of(
                () -> service.reserve(command(orderId, item("SKU-001", 3))),
                () -> service.reserve(command(orderId, item("SKU-001", 3)))));

        assertThat(jdbc.queryForObject("select count(*) from reservations where order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(stock.available("SKU-001")).contains(7);
        assertThat(results)
                .filteredOn(ReserveStockOutcome.Decided.class::isInstance)
                .hasSize(1);
        // The loser either hit the unique order_id constraint (to be retried by the container) or, once
        // it can see the winner's reservation, is ignored as a duplicate order (spec Story 4).
        assertThat(results)
                .filteredOn(result -> !(result instanceof ReserveStockOutcome.Decided))
                .singleElement()
                .satisfies(loser -> assertThat(loser)
                        .isInstanceOfAny(DataIntegrityViolationException.class, ReserveStockOutcome.class));
        verify(publisher, times(1)).publish(any());
    }

    private List<ReserveStockOutcome> runTogether(List<Callable<ReserveStockOutcome>> tasks) throws Exception {
        List<ReserveStockOutcome> outcomes = new ArrayList<>();
        for (Object result : runTogetherCapturingFailures(tasks)) {
            if (result instanceof Exception e) {
                throw e;
            }
            outcomes.add((ReserveStockOutcome) result);
        }
        return outcomes;
    }

    /** Starts every task at the same moment and returns each outcome or the exception it threw. */
    private List<Object> runTogetherCapturingFailures(List<Callable<ReserveStockOutcome>> tasks)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ReserveStockOutcome>> futures = new ArrayList<>();
        for (Callable<ReserveStockOutcome> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<ReserveStockOutcome> future : futures) {
            try {
                results.add(future.get(30, TimeUnit.SECONDS));
            } catch (ExecutionException e) {
                results.add(e.getCause());
            } catch (java.util.concurrent.TimeoutException e) {
                throw new AssertionError("A reservation did not complete within 30 s (deadlock?)", e);
            }
        }
        return results;
    }

    private static long statuses(List<ReserveStockOutcome> outcomes, ReservationStatus status) {
        return outcomes.stream()
                .filter(outcome -> outcome instanceof ReserveStockOutcome.Decided decided && decided.status() == status)
                .count();
    }

    private static ReserveStockCommand command(UUID orderId, RequestedItem... items) {
        return new ReserveStockCommand(UUID.randomUUID(), TOPIC, new OrderId(orderId), List.of(items));
    }

    private static RequestedItem item(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }
}
