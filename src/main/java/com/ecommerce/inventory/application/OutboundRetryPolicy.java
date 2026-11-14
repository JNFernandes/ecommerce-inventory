package com.ecommerce.inventory.application;

import java.time.Duration;
import java.util.Objects;

/**
 * How the retry worker redelivers outbound dead letters (US-03 FR-002, FR-009, FR-010). Pure value; the
 * backoff delay after failed attempt {@code n} is {@code baseDelay × multiplier^(n−1)}, never more than
 * {@code maxDelay}. No jitter: rows are created at different times, so their schedules are spread.
 *
 * @param batchSize maximum rows attempted per round
 * @param maxAttempts worker attempts before a row becomes Exhausted
 * @param baseDelay delay after the first failed attempt
 * @param multiplier factor applied to the delay after each further failure
 * @param maxDelay upper bound for one delay
 */
public record OutboundRetryPolicy(
        int batchSize, int maxAttempts, Duration baseDelay, double multiplier, Duration maxDelay) {

    /** Validates the policy, so invalid settings stop the service at startup (FR-016). */
    public OutboundRetryPolicy {
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be at least 1, was " + batchSize);
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max attempts must be at least 1, was " + maxAttempts);
        }
        if (baseDelay.isNegative() || baseDelay.isZero()) {
            throw new IllegalArgumentException("base delay must be positive, was " + baseDelay);
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0, was " + multiplier);
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException(
                    "max delay " + maxDelay + " must not be shorter than base delay " + baseDelay);
        }
    }

    /**
     * Backoff delay after a failed attempt.
     *
     * @param attempt number of the attempt that failed, starting at 1
     * @return the delay before the next attempt
     */
    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1, was " + attempt);
        }
        double millis = baseDelay.toMillis() * Math.pow(multiplier, attempt - 1.0);
        double capped = Math.min(millis, (double) maxDelay.toMillis());
        return Duration.ofMillis((long) capped);
    }

    /**
     * Whether a failed attempt was the last one allowed.
     *
     * @param attempt number of the attempt that failed, starting at 1
     * @return {@code true} when the row must become Exhausted
     */
    public boolean isExhausted(int attempt) {
        return attempt >= maxAttempts;
    }
}
