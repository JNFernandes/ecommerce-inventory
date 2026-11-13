package com.ecommerce.inventory.infrastructure.kafka;

import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

/**
 * Starts every Kafka listener container in the background once the application is ready.
 *
 * <p>Listener containers are created with {@code spring.kafka.listener.auto-startup=false}, so the
 * application context never waits for, or fails because of, an unreachable broker. When the
 * application is ready this component hands the start-up to an executor and returns immediately;
 * the Kafka consumers then keep retrying the connection on their own threads until the broker is
 * available.
 */
public final class KafkaListenerStarter {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaListenerStarter.class);

    private final KafkaListenerEndpointRegistry registry;
    private final Executor executor;

    /**
     * Creates the starter.
     *
     * @param registry registry holding every {@code @KafkaListener} container
     * @param executor executor that runs the start-up off the caller's thread
     */
    public KafkaListenerStarter(KafkaListenerEndpointRegistry registry, Executor executor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Schedules the start of all listener containers without blocking application start-up. */
    @EventListener(ApplicationReadyEvent.class)
    public void startListenersInBackground() {
        executor.execute(this::startListeners);
    }

    private void startListeners() {
        int containers = registry.getListenerContainers().size();
        LOG.info("Starting {} Kafka listener container(s) in the background", containers);
        try {
            registry.start();
            LOG.info("Kafka listener containers started: {}", registry.getListenerContainerIds());
        } catch (RuntimeException e) {
            LOG.error(
                    "Failed to start Kafka listener containers {}; the service keeps running without consumers",
                    registry.getListenerContainerIds(),
                    e);
        }
    }
}
