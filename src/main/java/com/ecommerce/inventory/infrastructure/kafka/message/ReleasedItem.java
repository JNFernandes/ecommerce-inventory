package com.ecommerce.inventory.infrastructure.kafka.message;

/**
 * One released item of {@code inventory.stock-released}.
 *
 * @param productId product identifier
 * @param quantity units given back (the consolidated reserved quantity); may exceed the 32-bit range
 */
public record ReleasedItem(String productId, long quantity) {}
