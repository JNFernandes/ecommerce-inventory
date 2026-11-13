package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes outbound messages synchronously, so a failure is known before the offset is acknowledged
 * and can be dead-lettered (research R7).
 */
@Component
public class KafkaOutboundMessagePublisher implements OutboundMessagePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaOutboundMessagePublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Duration publishTimeout;

    /**
     * Creates the publisher.
     *
     * @param kafkaTemplate Kafka template with string keys and values
     * @param properties service configuration (publish timeout)
     */
    public KafkaOutboundMessagePublisher(KafkaTemplate<String, String> kafkaTemplate, InventoryProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.publishTimeout = properties.kafka().publishTimeout();
    }

    @Override
    public void publish(OutboundMessage message) {
        try {
            kafkaTemplate
                    .send(message.topic(), message.key(), message.payload())
                    .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
            LOG.debug("Published event {} to {} with key {}", message.eventId(), message.topic(), message.key());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(message, e);
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            throw failure(message, e);
        }
    }

    private static PublishFailedException failure(OutboundMessage message, Exception cause) {
        return new PublishFailedException(
                "Could not publish event " + message.eventId() + " to " + message.topic() + " with key "
                        + message.key(),
                cause);
    }
}
