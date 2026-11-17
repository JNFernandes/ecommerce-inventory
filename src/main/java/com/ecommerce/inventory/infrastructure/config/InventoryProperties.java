package com.ecommerce.inventory.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service-specific configuration bound from the {@code inventory.*} properties.
 *
 * @param kafka topics, inbound retry policy and publish timeout
 * @param outboundDeadLetter settings for events that could not be published
 * @param stock settings for stock row locking
 * @param outboundRetry settings of the outbound dead-letter retry worker (US-03)
 * @param http settings of the read-only catalog API (US-04)
 */
@Validated
@ConfigurationProperties("inventory")
public record InventoryProperties(
        @Valid @NotNull Kafka kafka,
        @Valid @NotNull OutboundDeadLetter outboundDeadLetter,
        @Valid @NotNull Stock stock,
        @Valid @NotNull OutboundRetry outboundRetry,
        @Valid @NotNull Http http) {

    /**
     * HTTP settings of the read-only catalog (US-04).
     *
     * @param cors cross-origin access to the catalog
     */
    public record Http(@Valid @NotNull Cors cors) {}

    /**
     * Cross-origin access to the catalog (US-04 FR-023, constitution Principle II). Origins come only
     * from {@code CORS_ALLOWED_ORIGINS}; empty means no cross-origin access.
     *
     * @param allowedOrigins explicit origins such as {@code http://localhost:5173}; trimmed, blanks
     *     dropped, wildcards refused
     */
    public record Cors(List<String> allowedOrigins) {

        /**
         * Normalises the origins.
         *
         * @throws IllegalArgumentException if an origin contains a wildcard
         */
        public Cors {
            List<String> origins = allowedOrigins == null
                    ? List.of()
                    : allowedOrigins.stream()
                            .filter(Objects::nonNull)
                            .map(String::trim)
                            .filter(origin -> !origin.isEmpty())
                            .toList();
            if (origins.stream().anyMatch(origin -> origin.contains("*"))) {
                throw new IllegalArgumentException(
                        "CORS_ALLOWED_ORIGINS must list explicit origins; wildcards are not allowed");
            }
            allowedOrigins = List.copyOf(origins);
        }
    }

    /**
     * Outbound dead-letter retry worker settings (US-03 FR-016).
     *
     * @param enabled whether the worker runs on this instance
     * @param interval delay between the end of one round and the start of the next
     * @param batchSize maximum rows attempted per round
     * @param maxAttempts worker attempts before a row becomes Exhausted
     * @param baseDelay backoff delay after the first failed attempt
     * @param multiplier factor applied to the delay after each further failure
     * @param maxDelay upper bound for one backoff delay
     */
    public record OutboundRetry(
            boolean enabled,
            @NotNull Duration interval,
            @Min(1) int batchSize,
            @Min(1) int maxAttempts,
            @NotNull Duration baseDelay,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxDelay) {}

    /**
     * Kafka settings.
     *
     * @param topics topic names, defaulting to the names in {@code ecommerce-infra/docs/events.md}
     * @param consumerRetry retry policy applied before an inbound message is dead-lettered
     * @param publishTimeout maximum time a synchronous publish waits for the broker
     */
    public record Kafka(
            @Valid @NotNull Topics topics,
            @Valid @NotNull ConsumerRetry consumerRetry,
            @NotNull Duration publishTimeout) {}

    /**
     * Topic names.
     *
     * @param orderPlaced consumed {@code orders.order-placed} topic
     * @param stockReserved produced {@code inventory.stock-reserved} topic
     * @param stockRejected produced {@code inventory.stock-rejected} topic
     * @param orderCancelled consumed {@code orders.order-cancelled} topic
     * @param stockReleased produced {@code inventory.stock-released} topic
     */
    public record Topics(
            @NotBlank String orderPlaced,
            @NotBlank String stockReserved,
            @NotBlank String stockRejected,
            @NotBlank String orderCancelled,
            @NotBlank String stockReleased) {}

    /**
     * Exponential back-off for inbound processing failures.
     *
     * @param maxRetries retries after the first attempt
     * @param initialInterval delay before the first retry
     * @param multiplier factor applied to the delay after each retry
     * @param maxInterval upper bound for a single delay
     */
    public record ConsumerRetry(
            @Min(0) int maxRetries,
            @NotNull Duration initialInterval,
            @DecimalMin("1.0") double multiplier,
            @NotNull Duration maxInterval) {}

    /**
     * Outbound dead-letter settings.
     *
     * @param firstRetryDelay delay between a failed publish and its first scheduled retry
     */
    public record OutboundDeadLetter(@NotNull Duration firstRetryDelay) {}

    /**
     * Stock locking settings.
     *
     * @param lockTimeout maximum time a reservation waits for stock row locks
     */
    public record Stock(@NotNull Duration lockTimeout) {}
}
