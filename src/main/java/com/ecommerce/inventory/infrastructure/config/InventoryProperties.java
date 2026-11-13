package com.ecommerce.inventory.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Service-specific configuration bound from the {@code inventory.*} properties.
 *
 * @param kafka topics, inbound retry policy and publish timeout
 * @param outboundDeadLetter settings for events that could not be published
 * @param stock settings for stock row locking
 */
@Validated
@ConfigurationProperties("inventory")
public record InventoryProperties(
        @Valid @NotNull Kafka kafka,
        @Valid @NotNull OutboundDeadLetter outboundDeadLetter,
        @Valid @NotNull Stock stock) {

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
     */
    public record Topics(
            @NotBlank String orderPlaced,
            @NotBlank String stockReserved,
            @NotBlank String stockRejected) {}

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
