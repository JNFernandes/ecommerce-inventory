package com.ecommerce.inventory.infrastructure.kafka.message;

/**
 * One failing item of {@code inventory.stock-rejected}.
 *
 * @param productId product identifier
 * @param requested consolidated quantity requested; may exceed the 32-bit range
 * @param available quantity available at decision time; 0 for an unknown product
 */
public record RejectedItem(String productId, long requested, int available) {}
