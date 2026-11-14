package com.ecommerce.inventory.application;

import com.ecommerce.inventory.application.port.OutboundDeadLetter;
import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.application.port.PublishFailedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Redelivers outbound dead letters (US-03). A round attempts up to {@code batchSize} due rows, each in
 * its own transaction: claim the oldest due row with {@code FOR UPDATE SKIP LOCKED}, publish the stored
 * message unchanged through {@link OutboundMessagePublisher} (not {@link OutcomePublisher}, which would
 * dead-letter it again), and record the result before the commit releases the lock. The first failed
 * attempt ends the round (fail fast: the broker is probably down). Rounds never overlap in one
 * instance, never throw, and stop claiming once {@link #stop} is called.
 */
@Service
public class OutboundRetryService {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundRetryService.class);

    private final OutboundDeadLetterRepository deadLetters;
    private final OutboundMessagePublisher publisher;
    private final OutboundRetryPolicy policy;
    private final TransactionOperations transactions;
    private final Clock clock;
    private final ReentrantLock round = new ReentrantLock();
    private volatile boolean stopping;

    /**
     * Creates the service.
     *
     * @param deadLetters the outbound dead letters
     * @param publisher publishes to the broker
     * @param policy batch size, attempts and backoff
     * @param transactions one transaction per row
     * @param clock source of the resolved and next retry times
     */
    public OutboundRetryService(
            OutboundDeadLetterRepository deadLetters,
            OutboundMessagePublisher publisher,
            OutboundRetryPolicy policy,
            TransactionOperations transactions,
            Clock clock) {
        this.deadLetters = deadLetters;
        this.publisher = publisher;
        this.policy = policy;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Runs one retry round. Returns at once with nothing done when a round is already running or the
     * service is stopping. Never throws: an unexpected failure is logged and ends the round.
     *
     * @return what the round did
     */
    public RetryRoundResult retryDueBatch() {
        if (stopping || !round.tryLock()) {
            return RetryRoundResult.NONE;
        }
        try {
            RetryRoundResult result = runRound();
            if (result.claimed() > 0) {
                LOG.info(
                        "Outbound retry round: claimed={} resolved={} failed={} exhausted={}",
                        result.claimed(),
                        result.resolved(),
                        result.failed(),
                        result.exhausted());
            } else {
                LOG.debug("Outbound retry round: nothing due");
            }
            return result;
        } finally {
            round.unlock();
        }
    }

    /**
     * Stops claiming rows and waits for the round in progress, if any, to finish its current row.
     * Rows not yet claimed stay as they are.
     *
     * @param timeout maximum wait
     * @return {@code true} when no round is running any more
     */
    public boolean stop(Duration timeout) {
        stopping = true;
        try {
            if (round.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                round.unlock();
                return true;
            }
            LOG.warn("Outbound retry round still running after {}; stopping anyway", timeout);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RetryRoundResult runRound() {
        int resolved = 0;
        int failed = 0;
        int exhausted = 0;
        try {
            while (!stopping && resolved < policy.batchSize()) {
                Outcome outcome = transactions.execute(status -> attemptNextDue());
                if (outcome == null || outcome == Outcome.NOTHING_DUE) {
                    break;
                }
                if (outcome == Outcome.RESOLVED) {
                    resolved++;
                } else {
                    if (outcome == Outcome.FAILED) {
                        failed++;
                    } else {
                        exhausted++;
                    }
                    break;
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Outbound retry round stopped by an unexpected error; the next round tries again", e);
        }
        return new RetryRoundResult(resolved + failed + exhausted, resolved, failed, exhausted);
    }

    private Outcome attemptNextDue() {
        Optional<OutboundDeadLetter> claimed = deadLetters.claimNextDue();
        if (claimed.isEmpty()) {
            return Outcome.NOTHING_DUE;
        }
        OutboundDeadLetter row = claimed.get();
        try {
            publisher.publish(row.message());
        } catch (PublishFailedException e) {
            return recordFailure(row, e);
        }
        deadLetters.markResolved(row.id(), clock.instant());
        LOG.debug(
                "Outbound dead letter {} delivered (event {})",
                row.id(),
                row.message().eventId());
        return Outcome.RESOLVED;
    }

    private Outcome recordFailure(OutboundDeadLetter row, PublishFailedException failure) {
        int attempt = row.retryCount() + 1;
        String error = Failures.describe(failure);
        if (policy.isExhausted(attempt)) {
            deadLetters.markExhausted(row.id(), attempt, error);
            LOG.error(
                    "Outbound dead letter {} exhausted after {} attempts, no further retries:"
                            + " eventId={} topic={} key={} lastError={}",
                    row.id(),
                    attempt,
                    row.message().eventId(),
                    row.message().topic(),
                    row.message().key(),
                    error);
            return Outcome.EXHAUSTED;
        }
        Instant nextRetryAt = clock.instant().plus(policy.delayAfter(attempt));
        deadLetters.recordFailedAttempt(row.id(), attempt, error, nextRetryAt);
        LOG.warn(
                "Outbound retry of dead letter {} to {} failed, attempt {}/{}, next attempt at {}: {}",
                row.id(),
                row.message().topic(),
                attempt,
                policy.maxAttempts(),
                nextRetryAt,
                error);
        return Outcome.FAILED;
    }

    private enum Outcome {
        NOTHING_DUE,
        RESOLVED,
        FAILED,
        EXHAUSTED
    }
}
