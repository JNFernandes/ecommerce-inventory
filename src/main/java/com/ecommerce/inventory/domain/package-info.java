/**
 * Domain layer: the only home of Inventory business rules.
 *
 * <p>Holds the {@code StockItem} and {@code Reservation} aggregates, their value objects, domain
 * exceptions and the domain events {@code StockReserved}, {@code StockRejected} and
 * {@code StockReleased}. Stock never goes negative and a reservation is all-or-nothing per order.
 *
 * <p>This package is plain Java: it MUST NOT depend on Spring, JPA ({@code jakarta.persistence})
 * or Kafka. Repository interfaces the domain needs are declared here and implemented in
 * {@code infrastructure}.
 */
package com.ecommerce.inventory.domain;
