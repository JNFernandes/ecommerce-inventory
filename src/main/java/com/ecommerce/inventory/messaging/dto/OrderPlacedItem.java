package com.ecommerce.inventory.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One line of an {@code orders.order-placed} message.
 *
 * @param productId product identifier, for example {@code SKU-001}; compared exactly
 * @param quantity units ordered, a JSON integer greater than 0
 * @param unitPrice price per unit; not used by Inventory (prices are out of scope)
 */
public record OrderPlacedItem(
        @NotBlank @Size(max = 64) String productId,
        @NotNull @Positive Integer quantity,
        BigDecimal unitPrice) {}
