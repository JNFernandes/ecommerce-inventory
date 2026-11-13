package com.ecommerce.inventory.infrastructure.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

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
}
