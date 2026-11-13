package com.ecommerce.inventory.infrastructure.kafka.message;

/**
 * One reserved item of {@code inventory.stock-reserved}.
 *
 * @param productId product identifier
 * @param quantity units reserved (the consolidated quantity of the order)
 */
public record ReservedItem(String productId, long quantity) {}
