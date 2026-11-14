package com.ecommerce.inventory.messaging.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code orders.order-cancelled} message, published by Orders after a pending order is cancelled.
 * Contract: {@code ecommerce-infra/docs/events.md}.
 *
 * @param eventId unique id of this event; used for idempotency
 * @param occurredAt when the order was cancelled (ISO 8601)
 * @param aggregateId the order id
 * @param version schema version; only {@code 1} is supported
 * @param customerId the customer of the order; optional, not used by Inventory
 * @param reason why the order was cancelled, for example a payment failure; optional, not used
 */
public record OrderCancelledMessage(
        @NotNull UUID eventId,
        @NotNull Instant occurredAt,
        @NotNull UUID aggregateId,
        @NotNull Integer version,
        UUID customerId,
        String reason) {

    /** The only schema version this service understands. */
    public static final int SUPPORTED_VERSION = 1;

    /**
     * Whether the message uses the supported schema version.
     *
     * @return {@code true} when {@code version} is 1 (a missing version is reported by {@code @NotNull})
     */
    @AssertTrue(message = "version must be 1")
    public boolean isSupportedVersion() {
        return version == null || version == SUPPORTED_VERSION;
    }
}
