package com.ecommerce.inventory.infrastructure.config;

import com.ecommerce.inventory.application.DeadLetterPolicy;
import com.ecommerce.inventory.application.OutboundRetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Exposes configured policies to the application layer as plain values. */
@Configuration(proxyBeanMethods = false)
public class ApplicationPolicyConfiguration {

    /**
     * Outbound dead-letter policy from {@code inventory.outbound-dead-letter.*}.
     *
     * @param properties service configuration
     * @return the policy
     */
    @Bean
    public DeadLetterPolicy deadLetterPolicy(InventoryProperties properties) {
        return new DeadLetterPolicy(properties.outboundDeadLetter().firstRetryDelay());
    }

    /**
     * Outbound retry worker policy from {@code inventory.outbound-retry.*}. Invalid settings fail startup.
     *
     * @param properties service configuration
     * @return the policy
     */
    @Bean
    public OutboundRetryPolicy outboundRetryPolicy(InventoryProperties properties) {
        InventoryProperties.OutboundRetry retry = properties.outboundRetry();
        return new OutboundRetryPolicy(
                retry.batchSize(), retry.maxAttempts(), retry.baseDelay(), retry.multiplier(), retry.maxDelay());
    }
}
