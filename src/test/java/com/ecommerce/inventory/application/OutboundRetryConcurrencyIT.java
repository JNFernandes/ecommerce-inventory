package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Several service instances retrying at the same moment on one real PostgreSQL never publish a row
 * twice (US-03 FR-003, SC-003). Each instance is its own {@link OutboundRetryService}, as on separate
 * JVMs; they share only the database.
 */
class OutboundRetryConcurrencyIT extends AbstractPostgresIT {

    private static final int ROWS = 50;
    private static final int INSTANCES = 3;

    @Autowired
    private OutboundDeadLetterRepository deadLetters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    private final List<UUID> published = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
        published.clear();
    }

    @Test
    @DisplayName("should publish each of 50 due rows exactly once with 3 instances retrying at the same time")
    void shouldPublishEachRowOnce() throws Exception {
        Set<UUID> expected = new HashSet<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(insertDue());
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        try {
            List<Future<Long>> instances = new ArrayList<>();
            for (int i = 0; i < INSTANCES; i++) {
                OutboundRetryService instance = newInstance(10);
                instances.add(pool.submit(() -> {
                    start.await();
                    long longestRoundMillis = 0;
                    while (pending() > 0) {
                        long begin = System.nanoTime();
                        instance.retryDueBatch();
                        longestRoundMillis =
                                Math.max(longestRoundMillis, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin));
                    }
                    return longestRoundMillis;
                }));
            }
            start.countDown();
            for (Future<Long> instance : instances) {
                assertThat(instance.get(60, TimeUnit.SECONDS)).isLessThan(5_000);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(published).hasSize(ROWS).doesNotHaveDuplicates();
        assertThat(new HashSet<>(published)).isEqualTo(expected);
        assertThat(jdbc.queryForObject(
                        "select count(*) from outbound_dead_letters where status = 'RESOLVED'", Integer.class))
                .isEqualTo(ROWS);
    }

    @Test
    @DisplayName("should skip a row locked by another instance and deliver the others")
    void shouldSkipRowLockedByAnotherInstance() throws Exception {
        UUID locked = insertDue();
        UUID free = insertDue();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<UUID>> holder = pool.submit(() -> transactions.execute(status -> {
                Optional<UUID> eventId =
                        deadLetters.claimNextDue().map(row -> row.message().eventId());
                claimed.countDown();
                awaitQuietly(release);
                return eventId;
            }));
            assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

            RetryRoundResult result = newInstance(10).retryDueBatch();
            release.countDown();

            assertThat(holder.get(10, TimeUnit.SECONDS)).contains(locked);
            assertThat(result).isEqualTo(new RetryRoundResult(1, 1, 0, 0));
            assertThat(published).containsExactly(free);
        } finally {
            pool.shutdownNow();
        }
    }

    private OutboundRetryService newInstance(int batchSize) {
        OutboundMessagePublisher publisher = (OutboundMessage message) -> {
            published.add(message.eventId());
            sleep(Duration.ofMillis(20));
        };
        return new OutboundRetryService(
                deadLetters,
                publisher,
                new OutboundRetryPolicy(batchSize, 10, Duration.ofSeconds(30), 2.0, Duration.ofHours(1)),
                new TransactionTemplate(transactionManager),
                clock);
    }

    private UUID insertDue() {
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "insert into outbound_dead_letters (id, event_id, topic, message_key, payload, status, retry_count,"
                        + " next_retry_at, last_error, created_at, updated_at)"
                        + " values (?, ?, 'inventory.stock-reserved', 'k', '{}'::jsonb, 'PENDING', 0,"
                        + " now() - interval '1 second', 'broker down', now(), now())",
                UUID.randomUUID(),
                eventId);
        return eventId;
    }

    private int pending() {
        Integer pending = jdbc.queryForObject(
                "select count(*) from outbound_dead_letters where status = 'PENDING'", Integer.class);
        return pending == null ? 0 : pending;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
