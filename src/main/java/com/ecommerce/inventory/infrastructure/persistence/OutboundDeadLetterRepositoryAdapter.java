package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores outcome messages that could not be published in {@code outbound_dead_letters} (FR-023), in a
 * transaction of their own: the reservation is already committed and is never rolled back. A repeated
 * failure for the same event id updates {@code last_error} instead of adding a row. The retry worker is
 * US-03.
 */
@Repository
public class OutboundDeadLetterRepositoryAdapter implements OutboundDeadLetterRepository {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    OutboundDeadLetterRepositoryAdapter(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(OutboundMessage message, String error, Instant nextRetryAt) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                insert into outbound_dead_letters
                    (id, event_id, topic, message_key, payload, status, retry_count, next_retry_at, last_error,
                     created_at, updated_at)
                values (?, ?, ?, ?, cast(? as jsonb), 'PENDING', 0, ?, ?, ?, ?)
                on conflict (event_id) do update
                    set last_error = excluded.last_error, updated_at = excluded.updated_at
                """,
                UUID.randomUUID(),
                message.eventId(),
                message.topic(),
                message.key(),
                message.payload(),
                OffsetDateTime.ofInstant(nextRetryAt, ZoneOffset.UTC),
                error,
                now,
                now);
    }
}
