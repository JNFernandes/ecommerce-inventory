package com.ecommerce.inventory.infrastructure.scheduling;

import com.ecommerce.inventory.application.OutboundRetryService;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs an outbound retry round every {@code inventory.outbound-retry.interval}, measured from the end of
 * the previous round, so rounds never overlap (US-03 FR-001, FR-004). Never connects to Kafka during
 * startup: the first round runs one interval after the context is ready.
 *
 * <p>As a {@link SmartLifecycle} in the highest phase it stops first on shutdown, before the data source
 * and the Kafka producer are closed: no new round starts and the row in progress is finished (FR-015).
 */
@Component
@ConditionalOnProperty(name = "inventory.outbound-retry.enabled", havingValue = "true", matchIfMissing = true)
public class OutboundRetryScheduler implements SmartLifecycle {

    private static final Duration SHUTDOWN_MARGIN = Duration.ofSeconds(5);

    private final OutboundRetryService service;
    private final Duration shutdownTimeout;
    private volatile boolean running;

    OutboundRetryScheduler(OutboundRetryService service, InventoryProperties properties) {
        this.service = service;
        this.shutdownTimeout = properties.kafka().publishTimeout().plus(SHUTDOWN_MARGIN);
    }

    /** Runs one round while the scheduler is running. */
    @Scheduled(
            fixedDelayString = "${inventory.outbound-retry.interval}",
            initialDelayString = "${inventory.outbound-retry.interval}")
    public void retryDueDeadLetters() {
        if (running) {
            service.retryDueBatch();
        }
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        service.stop(shutdownTimeout);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
