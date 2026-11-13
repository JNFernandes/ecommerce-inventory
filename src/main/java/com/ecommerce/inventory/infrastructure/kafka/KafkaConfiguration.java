package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository;
import com.ecommerce.inventory.messaging.InvalidMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** Kafka wiring that complements Spring Boot's auto-configuration. */
@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    /**
     * Starts the listener containers on a virtual thread once the application is ready.
     *
     * @param registry registry holding every {@code @KafkaListener} container
     * @return the background listener starter
     */
    @Bean
    public KafkaListenerStarter kafkaListenerStarter(KafkaListenerEndpointRegistry registry) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("kafka-listener-starter-");
        executor.setVirtualThreads(true);
        return new KafkaListenerStarter(registry, executor);
    }

    /**
     * Error handler applied by Spring Boot to the listener containers (research R6, FR-024): failures
     * are retried with exponential back-off, then the record is stored in {@code consumed_dead_letters}
     * and its offset committed so the partition keeps moving. Invalid messages are never retried
     * (FR-003). {@code commitRecovered} requires the {@code manual_immediate} ack mode.
     *
     * @param deadLetters consumed-message dead-letter store
     * @param properties retry configuration
     * @return the error handler
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            ConsumedDeadLetterRepository deadLetters, InventoryProperties properties) {
        InventoryProperties.ConsumerRetry retry = properties.kafka().consumerRetry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        DefaultErrorHandler handler =
                new DefaultErrorHandler(new ConsumedDeadLetterRecoverer(deadLetters, retry.maxRetries()), backOff);
        handler.addNotRetryableExceptions(InvalidMessageException.class);
        handler.setCommitRecovered(true);
        handler.setAckAfterHandle(true);
        return handler;
    }
}
