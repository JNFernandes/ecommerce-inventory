/**
 * Messaging layer: Kafka listeners.
 *
 * <p>This is the only input that changes state; HTTP (package {@code web}) only reads the catalog.
 *
 * <p>Listeners deserialize each record into an immutable, Bean-Validation-annotated record DTO,
 * validate it and call exactly one application service method. They contain no business logic and
 * acknowledge the offset only after the database save. Consumed topics:
 * {@code orders.order-placed} and {@code orders.order-cancelled}; contracts are defined in
 * {@code ecommerce-infra/docs/events.md}.
 */
package com.ecommerce.inventory.messaging;
