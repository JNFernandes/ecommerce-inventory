package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.application.port.OutboundDeadLetter;
import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository.ConsumedRecord;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.TestKafkaClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DeadLetterAdaptersIT extends AbstractPostgresIT {

    private static final OutboundMessage MESSAGE = new OutboundMessage(
            UUID.randomUUID(), "inventory.stock-reserved", "res-1", "{\"version\":1,\"orderId\":\"o-1\"}");

    @Autowired
    private OutboundDeadLetterRepository outbound;

    @Autowired
    private ConsumedDeadLetterRepository consumed;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("should store a failed publish as PENDING with retry count 0, next retry time and last error")
    void shouldStoreOutboundDeadLetter() {
        Instant nextRetryAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);

        outbound.recordFailure(MESSAGE, "broker down", nextRetryAt);

        Map<String, Object> row = jdbc.queryForMap("select * from outbound_dead_letters");
        assertThat(row.get("event_id")).isEqualTo(MESSAGE.eventId());
        assertThat(row.get("topic")).isEqualTo("inventory.stock-reserved");
        assertThat(row.get("message_key")).isEqualTo("res-1");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("retry_count")).isEqualTo(0);
        assertThat(row.get("last_error")).isEqualTo("broker down");
        assertThat(row.get("resolved_at")).isNull();
        assertThat(columnEquals("next_retry_at", nextRetryAt)).isTrue();
        assertThat(TestKafkaClient.json(
                        jdbc.queryForObject("select payload::text from outbound_dead_letters", String.class)))
                .isEqualTo(TestKafkaClient.json(MESSAGE.payload()));
    }

    @Test
    @DisplayName("should only update the last error of a PENDING row for a repeated failure")
    void shouldUpdateRepeatedOutboundFailure() {
        outbound.recordFailure(MESSAGE, "first", Instant.now().minusSeconds(60));
        jdbc.update("update outbound_dead_letters set retry_count = 2");
        Object nextRetryAt = jdbc.queryForObject("select next_retry_at from outbound_dead_letters", Object.class);

        outbound.recordFailure(MESSAGE, "second", Instant.now().plusSeconds(3600));

        Map<String, Object> row = jdbc.queryForMap("select * from outbound_dead_letters");
        assertThat(jdbc.queryForObject("select count(*) from outbound_dead_letters", Integer.class))
                .isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("second");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("retry_count")).isEqualTo(2);
        assertThat(row.get("next_retry_at")).isEqualTo(nextRetryAt);
    }

    @Test
    @DisplayName("should reopen a RESOLVED row as PENDING with retry count 0 when it fails again")
    void shouldReopenResolvedRowOnRepeatedFailure() {
        outbound.recordFailure(MESSAGE, "first", Instant.now());
        jdbc.update("update outbound_dead_letters set status = 'RESOLVED', resolved_at = now(), retry_count = 3");
        Instant nextRetryAt = Instant.now().plusSeconds(30).truncatedTo(ChronoUnit.MICROS);

        outbound.recordFailure(MESSAGE, "again", nextRetryAt);

        Map<String, Object> row = jdbc.queryForMap("select * from outbound_dead_letters");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("retry_count")).isEqualTo(0);
        assertThat(row.get("resolved_at")).isNull();
        assertThat(row.get("last_error")).isEqualTo("again");
        assertThat(columnEquals("next_retry_at", nextRetryAt)).isTrue();
    }

    @Test
    @DisplayName("should keep an EXHAUSTED row exhausted and only store the new error when it fails again")
    void shouldKeepExhaustedRowOnRepeatedFailure() {
        outbound.recordFailure(MESSAGE, "first", Instant.now());
        jdbc.update("update outbound_dead_letters set status = 'EXHAUSTED', retry_count = 10");

        outbound.recordFailure(MESSAGE, "again", Instant.now());

        Map<String, Object> row = jdbc.queryForMap("select * from outbound_dead_letters");
        assertThat(row.get("status")).isEqualTo("EXHAUSTED");
        assertThat(row.get("retry_count")).isEqualTo(10);
        assertThat(row.get("last_error")).isEqualTo("again");
    }

    @Test
    @DisplayName("should claim only due PENDING rows, oldest next retry time first, and nothing when none is due")
    void shouldClaimOldestDuePendingRow() {
        UUID newer = insert("PENDING", "now() - interval '1 minute'");
        UUID older = insert("PENDING", "now() - interval '2 minutes'");
        insert("PENDING", "now() + interval '1 hour'");
        insert("EXHAUSTED", "now() - interval '1 hour'");
        jdbc.update(
                "update outbound_dead_letters set status = 'RESOLVED', resolved_at = now() where id = ?",
                insert("PENDING", "now() - interval '1 hour'"));

        assertThat(claimAndResolve()).map(OutboundDeadLetter::id).contains(older);
        assertThat(claimAndResolve()).map(OutboundDeadLetter::id).contains(newer);
        assertThat(claimAndResolve()).isEmpty();
    }

    @Test
    @DisplayName("should return the stored message and retry count of the claimed row")
    void shouldReturnStoredMessage() {
        outbound.recordFailure(MESSAGE, "broker down", Instant.now().minusSeconds(1));
        jdbc.update("update outbound_dead_letters set retry_count = 4");

        Optional<OutboundDeadLetter> claimed = transactions.execute(status -> outbound.claimNextDue());

        assertThat(claimed).isPresent();
        OutboundDeadLetter row = claimed.orElseThrow();
        assertThat(row.message().eventId()).isEqualTo(MESSAGE.eventId());
        assertThat(row.message().topic()).isEqualTo(MESSAGE.topic());
        assertThat(row.message().key()).isEqualTo(MESSAGE.key());
        assertThat(TestKafkaClient.json(row.message().payload())).isEqualTo(TestKafkaClient.json(MESSAGE.payload()));
        assertThat(row.retryCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("should skip a row locked by another transaction and claim the next due row without waiting")
    void shouldSkipLockedRow() throws Exception {
        UUID first = insert("PENDING", "now() - interval '2 minutes'");
        UUID second = insert("PENDING", "now() - interval '1 minute'");
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Optional<UUID>> holder = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
            Optional<UUID> id = outbound.claimNextDue().map(OutboundDeadLetter::id);
            claimed.countDown();
            awaitLatch(release);
            return id;
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        Optional<UUID> other =
                transactions.execute(status -> outbound.claimNextDue().map(OutboundDeadLetter::id));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        release.countDown();

        assertThat(holder.get(10, TimeUnit.SECONDS)).contains(first);
        assertThat(other).contains(second);
        assertThat(millis).isLessThan(2_000);
    }

    @Test
    @DisplayName("should refuse to claim outside a transaction")
    void shouldRefuseClaimOutsideTransaction() {
        assertThatThrownBy(() -> outbound.claimNextDue()).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("should mark a claimed row RESOLVED with its resolved time")
    void shouldMarkResolved() {
        UUID id = insert("PENDING", "now() - interval '1 second'");
        Instant resolvedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        transactions.executeWithoutResult(status -> outbound.markResolved(id, resolvedAt));

        assertThat(jdbc.queryForObject("select status from outbound_dead_letters", String.class))
                .isEqualTo("RESOLVED");
        assertThat(columnEquals("resolved_at", resolvedAt)).isTrue();
    }

    @Test
    @DisplayName("should record a failed attempt and keep the row PENDING")
    void shouldRecordFailedAttempt() {
        UUID id = insert("PENDING", "now() - interval '1 second'");
        Instant nextRetryAt = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);

        transactions.executeWithoutResult(status -> outbound.recordFailedAttempt(id, 1, "timeout", nextRetryAt));

        Map<String, Object> row = jdbc.queryForMap("select * from outbound_dead_letters");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat(row.get("last_error")).isEqualTo("timeout");
        assertThat(row.get("resolved_at")).isNull();
        assertThat(columnEquals("next_retry_at", nextRetryAt)).isTrue();
    }

    @Test
    @DisplayName("should mark a row EXHAUSTED with its attempts and last error")
    void shouldMarkExhausted() {
        UUID id = insert("PENDING", "now() - interval '1 second'");

        transactions.executeWithoutResult(status -> outbound.markExhausted(id, 10, "still down"));

        Map<String, Object> row = jdbc.queryForMap("select * from outbound_dead_letters");
        assertThat(row.get("status")).isEqualTo("EXHAUSTED");
        assertThat(row.get("retry_count")).isEqualTo(10);
        assertThat(row.get("last_error")).isEqualTo("still down");
    }

    @Test
    @DisplayName("should store a consumed record verbatim, including non-JSON text, key and position")
    void shouldStoreConsumedDeadLetter() {
        consumed.record(new ConsumedRecord("orders.order-placed", 2, 42L, "k-1", "not json at all"), "boom", 1);

        Map<String, Object> row = jdbc.queryForMap("select * from consumed_dead_letters");
        assertThat(row.get("topic")).isEqualTo("orders.order-placed");
        assertThat(row.get("partition")).isEqualTo(2);
        assertThat(row.get("record_offset")).isEqualTo(42L);
        assertThat(row.get("message_key")).isEqualTo("k-1");
        assertThat(row.get("payload")).isEqualTo("not json at all");
        assertThat(row.get("error")).isEqualTo("boom");
        assertThat(row.get("attempts")).isEqualTo(1);
    }

    @Test
    @DisplayName("should store a consumed record with null key and value")
    void shouldStoreNullKeyAndValue() {
        consumed.record(new ConsumedRecord("orders.order-placed", 0, 1L, null, null), "empty", 1);

        assertThat(jdbc.queryForObject(
                        "select count(*) from consumed_dead_letters where payload is null and message_key is null",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("should keep one row when the same record is dead-lettered twice")
    void shouldBeIdempotentPerRecord() {
        ConsumedRecord record = new ConsumedRecord("orders.order-placed", 0, 7L, "k", "v");

        consumed.record(record, "first", 3);
        consumed.record(record, "second", 3);

        assertThat(jdbc.queryForObject("select count(*) from consumed_dead_letters", Integer.class))
                .isEqualTo(1);
    }

    private Optional<OutboundDeadLetter> claimAndResolve() {
        return transactions.execute(status -> {
            Optional<OutboundDeadLetter> row = outbound.claimNextDue();
            row.ifPresent(r -> outbound.markResolved(r.id(), Instant.now()));
            return row;
        });
    }

    private Boolean columnEquals(String column, Instant expected) {
        return jdbc.queryForObject(
                "select " + column + " = ? from outbound_dead_letters",
                Boolean.class,
                OffsetDateTime.ofInstant(expected, ZoneOffset.UTC));
    }

    private UUID insert(String status, String nextRetryAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into outbound_dead_letters (id, event_id, topic, message_key, payload, status, retry_count,"
                        + " next_retry_at, last_error, created_at, updated_at)"
                        + " values (?, ?, 'inventory.stock-reserved', 'k', '{}'::jsonb, ?, 0, "
                        + nextRetryAtSql
                        + ", 'e', now(), now())",
                id,
                UUID.randomUUID(),
                status);
        return id;
    }

    private static void awaitLatch(CountDownLatch latch) {
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
