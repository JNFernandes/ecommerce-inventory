package com.ecommerce.inventory.application;

/**
 * What one retry round did. {@code claimed = resolved + failed + exhausted}.
 *
 * @param claimed rows claimed and attempted
 * @param resolved rows delivered
 * @param failed rows whose attempt failed and that will be retried
 * @param exhausted rows whose last allowed attempt failed
 */
public record RetryRoundResult(int claimed, int resolved, int failed, int exhausted) {

    /** A round that claimed nothing. */
    public static final RetryRoundResult NONE = new RetryRoundResult(0, 0, 0, 0);
}
