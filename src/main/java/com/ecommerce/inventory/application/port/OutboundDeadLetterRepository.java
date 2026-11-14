package com.ecommerce.inventory.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Stores messages that could not be published after the commit (FR-023) and serves them to the retry worker. */
public interface OutboundDeadLetterRepository {

    /**
     * Records a failed publish in its own transaction. A second failure for the same event id never adds
     * a row: a {@code PENDING} or {@code EXHAUSTED} row only gets the new last error; a {@code RESOLVED}
     * row becomes {@code PENDING} again with retry count 0, the new next retry time and no resolved time
     * (US-03 FR-012).
     *
     * @param message the message that was not published
     * @param error description of the failure
     * @param nextRetryAt when the retry worker may try again
     */
    void recordFailure(OutboundMessage message, String error, Instant nextRetryAt);

    /**
     * Claims the oldest due {@code PENDING} row. Must run inside a transaction: the row stays locked until
     * that transaction ends. Rows locked by other transactions are skipped, never waited for.
     *
     * @return the claimed row, or empty when none is due
     */
    Optional<OutboundDeadLetter> claimNextDue();

    /**
     * Marks a claimed row as delivered. Must run inside the claiming transaction.
     *
     * @param id row id
     * @param resolvedAt delivery time
     */
    void markResolved(UUID id, Instant resolvedAt);

    /**
     * Records a failed worker attempt; the row stays {@code PENDING}. Must run inside the claiming
     * transaction.
     *
     * @param id row id
     * @param retryCount attempts made, including this one
     * @param error description of the failure
     * @param nextRetryAt when the next attempt is due
     */
    void recordFailedAttempt(UUID id, int retryCount, String error, Instant nextRetryAt);

    /**
     * Gives up on a row after its last allowed attempt. Must run inside the claiming transaction.
     *
     * @param id row id
     * @param retryCount attempts made, including the last one
     * @param error description of the last failure
     */
    void markExhausted(UUID id, int retryCount, String error);
}
