package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.application.port.OrderLock;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/** The per-order advisory lock serialises transactions on the same order only (ADR 0005). */
@TestPropertySource(properties = "inventory.stock.lock-timeout=1500ms")
class PostgresOrderLockIT extends AbstractPostgresIT {

    @Autowired
    private OrderLock orderLock;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    @DisplayName("should make a second transaction on the same order wait until the first commits")
    void shouldSerialiseTheSameOrder() throws Exception {
        OrderId order = new OrderId(UUID.randomUUID());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Long> holder = holdLock(order, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Long> waiter = CompletableFuture.supplyAsync(() -> {
            transactions.executeWithoutResult(status -> orderLock.lock(order));
            return System.nanoTime();
        });
        Thread.onSpinWait();
        assertThat(waiter).isNotDone();
        long releasedAt = System.nanoTime();
        release.countDown();

        long acquiredAt = waiter.get(10, TimeUnit.SECONDS);
        holder.get(10, TimeUnit.SECONDS);
        assertThat(acquiredAt).isGreaterThan(releasedAt);
    }

    @Test
    @DisplayName("should not make a transaction on another order wait")
    void shouldNotBlockOtherOrders() throws Exception {
        OrderId order = new OrderId(UUID.randomUUID());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Long> holder = holdLock(order, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            CompletableFuture<Void> other = CompletableFuture.runAsync(
                    () -> transactions.executeWithoutResult(status -> orderLock.lock(new OrderId(UUID.randomUUID()))));
            other.get(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("should fail with a lock timeout instead of waiting forever")
    void shouldTimeOut() throws Exception {
        OrderId order = new OrderId(UUID.randomUUID());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Long> holder = holdLock(order, locked, release);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> orderLock.lock(order)))
                    .isInstanceOf(PessimisticLockingFailureException.class);
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("should refuse to lock outside a transaction")
    void shouldRequireTransaction() {
        assertThatThrownBy(() -> orderLock.lock(new OrderId(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private CompletableFuture<Long> holdLock(OrderId order, CountDownLatch locked, CountDownLatch release) {
        return CompletableFuture.supplyAsync(() -> {
            transactions.executeWithoutResult(status -> {
                orderLock.lock(order);
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Lock holder was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            });
            return System.nanoTime();
        });
    }
}
