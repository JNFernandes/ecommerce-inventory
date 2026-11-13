/**
 * Root package of the Inventory service.
 *
 * <p>Layers, from the outside in: {@code messaging} (Kafka listeners) → {@code application}
 * (use-case orchestration and transactions) → {@code domain} (business rules) and
 * {@code infrastructure} (persistence, Kafka producers, configuration). See the project
 * constitution, Principle I.
 */
package com.ecommerce.inventory;
