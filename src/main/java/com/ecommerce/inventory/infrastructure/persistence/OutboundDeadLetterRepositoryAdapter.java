package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.port.OutboundDeadLetter;
import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores outcome messages that could not be published in {@code outbound_dead_letters} (FR-023) and serves
 * them to the retry worker (US-03). {@link #recordFailure} runs in a transaction of its own: the
 * reservation is already committed and is never rolled back. The worker methods require the caller's
 * transaction, which holds the {@code FOR UPDATE SKIP LOCKED} row lock.
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
        OffsetDateTime now = utc(clock.instant());
        jdbcTemplate.update(
                """
                insert into outbound_dead_letters
                    (id, event_id, topic, message_key, payload, status, retry_count, next_retry_at, last_error,
                     created_at, updated_at)
                values (?, ?, ?, ?, cast(? as jsonb), 'PENDING', 0, ?, ?, ?, ?)
                on conflict (event_id) do update
                    set last_error = excluded.last_error,
                        updated_at = excluded.updated_at,
                        status = case when outbound_dead_letters.status = 'RESOLVED'
                            then 'PENDING' else outbound_dead_letters.status end,
                        retry_count = case when outbound_dead_letters.status = 'RESOLVED'
                            then 0 else outbound_dead_letters.retry_count end,
                        next_retry_at = case when outbound_dead_letters.status = 'RESOLVED'
                            then excluded.next_retry_at else outbound_dead_letters.next_retry_at end,
                        resolved_at = case when outbound_dead_letters.status = 'RESOLVED'
                            then null else outbound_dead_letters.resolved_at end
                """,
                UUID.randomUUID(),
                message.eventId(),
                message.topic(),
                message.key(),
                message.payload(),
                utc(nextRetryAt),
                error,
                now,
                now);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<OutboundDeadLetter> claimNextDue() {
        List<OutboundDeadLetter> rows = jdbcTemplate.query(
                """
                select id, event_id, topic, message_key, payload::text as payload, retry_count
                from outbound_dead_letters
                where status = 'PENDING' and next_retry_at <= now()
                order by next_retry_at
                limit 1
                for update skip locked
                """,
                (rs, rowNum) -> new OutboundDeadLetter(
                        rs.getObject("id", UUID.class),
                        new OutboundMessage(
                                rs.getObject("event_id", UUID.class),
                                rs.getString("topic"),
                                rs.getString("message_key"),
                                rs.getString("payload")),
                        rs.getInt("retry_count")));
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markResolved(UUID id, Instant resolvedAt) {
        jdbcTemplate.update(
                "update outbound_dead_letters set status = 'RESOLVED', resolved_at = ?, updated_at = ? where id = ?",
                utc(resolvedAt),
                utc(clock.instant()),
                id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailedAttempt(UUID id, int retryCount, String error, Instant nextRetryAt) {
        jdbcTemplate.update(
                "update outbound_dead_letters set retry_count = ?, last_error = ?, next_retry_at = ?, updated_at = ?"
                        + " where id = ?",
                retryCount,
                error,
                utc(nextRetryAt),
                utc(clock.instant()),
                id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markExhausted(UUID id, int retryCount, String error) {
        jdbcTemplate.update(
                "update outbound_dead_letters set status = 'EXHAUSTED', retry_count = ?, last_error = ?,"
                        + " updated_at = ? where id = ?",
                retryCount,
                error,
                utc(clock.instant()),
                id);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
