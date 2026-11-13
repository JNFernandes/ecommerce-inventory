package com.ecommerce.inventory.infrastructure.persistence;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores consumed messages that could not be processed in {@code consumed_dead_letters} (FR-003,
 * FR-024). The raw value is kept exactly as received. Recording the same topic, partition and offset
 * twice keeps one row, so recovery can safely run again.
 */
@Repository
public class ConsumedDeadLetterRepository {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    ConsumedDeadLetterRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Records a consumed message that could not be processed.
     *
     * @param record where the message came from and its raw key and value
     * @param error description of the last failure
     * @param attempts how many times processing was attempted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ConsumedRecord record, String error, int attempts) {
        jdbcTemplate.update(
                """
                insert into consumed_dead_letters
                    (id, topic, partition, record_offset, message_key, payload, error, attempts, failed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (topic, partition, record_offset) do nothing
                """,
                UUID.randomUUID(),
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value(),
                error,
                attempts,
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * Identity and raw content of a consumed record.
     *
     * @param topic topic it was consumed from
     * @param partition partition
     * @param offset offset in the partition
     * @param key raw key, may be null
     * @param value raw value as received, may be null
     */
    public record ConsumedRecord(String topic, int partition, long offset, String key, String value) {}
}
