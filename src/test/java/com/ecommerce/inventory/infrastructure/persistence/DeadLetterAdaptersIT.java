package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository.ConsumedRecord;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.TestKafkaClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class DeadLetterAdaptersIT extends AbstractPostgresIT {

    private static final OutboundMessage MESSAGE = new OutboundMessage(
            UUID.randomUUID(), "inventory.stock-reserved", "res-1", "{\"version\":1,\"orderId\":\"o-1\"}");

    @Autowired
    private OutboundDeadLetterRepository outbound;

    @Autowired
    private ConsumedDeadLetterRepository consumed;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
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
        assertThat(jdbc.queryForObject(
                        "select next_retry_at = ? from outbound_dead_letters",
                        Boolean.class,
                        java.time.OffsetDateTime.ofInstant(nextRetryAt, java.time.ZoneOffset.UTC)))
                .isTrue();
        assertThat(TestKafkaClient.json(
                        jdbc.queryForObject("select payload::text from outbound_dead_letters", String.class)))
                .isEqualTo(TestKafkaClient.json(MESSAGE.payload()));
    }

    @Test
    @DisplayName("should update the last error instead of adding a row for a repeated failure")
    void shouldUpdateRepeatedOutboundFailure() {
        outbound.recordFailure(MESSAGE, "first", Instant.now());
        outbound.recordFailure(MESSAGE, "second", Instant.now());

        assertThat(jdbc.queryForObject("select count(*) from outbound_dead_letters", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_error from outbound_dead_letters", String.class))
                .isEqualTo("second");
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
}
