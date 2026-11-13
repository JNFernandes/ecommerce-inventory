/**
 * Kafka infrastructure: client configuration and lifecycle.
 *
 * <p>The service starts and stays healthy while Kafka is unreachable: nothing connects to a broker
 * during startup, listener containers are started in the background once the application is ready,
 * and producers connect lazily on first send.
 */
package com.ecommerce.inventory.infrastructure.kafka;
