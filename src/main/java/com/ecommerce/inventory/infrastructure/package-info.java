/**
 * Infrastructure layer: technical adapters and configuration.
 *
 * <p>Contains the JPA persistence model and repository implementations (mapped explicitly to and
 * from domain objects, never exposed to the domain), the Kafka producer and its dead-letter retry
 * worker, and Spring configuration. Schema changes are made only through Flyway migrations.
 */
package com.ecommerce.inventory.infrastructure;
