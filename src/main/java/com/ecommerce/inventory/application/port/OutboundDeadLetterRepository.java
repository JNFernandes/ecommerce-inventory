package com.ecommerce.inventory.application.port;

import java.time.Instant;

/** Stores messages that could not be published after the commit (FR-023). */
public interface OutboundDeadLetterRepository {

    /**
     * Records a failed publish in its own transaction. A second failure for the same event id updates
     * the last error instead of adding a row.
     *
     * @param message the message that was not published
     * @param error description of the failure
     * @param nextRetryAt when the retry worker may try again
     */
    void recordFailure(OutboundMessage message, String error, Instant nextRetryAt);
}
