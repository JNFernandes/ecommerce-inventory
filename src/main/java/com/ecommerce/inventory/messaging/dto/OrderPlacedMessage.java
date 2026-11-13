package com.ecommerce.inventory.messaging.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The {@code orders.order-placed} message, published by Orders after an order is saved. Contract:
 * {@code ecommerce-infra/docs/events.md}.
 *
 * @param eventId unique id of this event; used for idempotency
 * @param occurredAt when the order was placed (ISO 8601)
 * @param aggregateId the order id
 * @param version schema version; only {@code 1} is supported
 * @param customerId the customer who placed the order; not used by Inventory
 * @param items order lines, at least one
 * @param totalAmount sum of all item subtotals; not used by Inventory
 */
public record OrderPlacedMessage(
        @NotNull UUID eventId,
        @NotNull Instant occurredAt,
        @NotNull UUID aggregateId,
        @NotNull Integer version,
        UUID customerId,
        @NotEmpty List<@Valid @NotNull OrderPlacedItem> items,
        BigDecimal totalAmount) {

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
