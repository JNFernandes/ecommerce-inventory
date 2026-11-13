package com.ecommerce.inventory.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class KafkaOutboundMessagePublisherTest {

    private static final OutboundMessage MESSAGE =
            new OutboundMessage(UUID.randomUUID(), "inventory.stock-reserved", "key-1", "{\"a\":1}");

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaOutboundMessagePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaOutboundMessagePublisher(kafkaTemplate, properties(Duration.ofMillis(100)));
    }

    @Test
    @DisplayName("should send the payload with the key to the topic and wait for the acknowledgement")
    void shouldSendWithKey() {
        when(kafkaTemplate.send("inventory.stock-reserved", "key-1", "{\"a\":1}"))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, null)));

        assertThatCode(() -> publisher.publish(MESSAGE)).doesNotThrowAnyException();
        verify(kafkaTemplate).send("inventory.stock-reserved", "key-1", "{\"a\":1}");
    }

    @Test
    @DisplayName("should throw PublishFailedException when the send future fails")
    void shouldFailWhenFutureFails() {
        when(kafkaTemplate.send("inventory.stock-reserved", "key-1", "{\"a\":1}"))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(PublishFailedException.class)
                .hasMessageContaining(MESSAGE.eventId().toString())
                .hasRootCauseMessage("broker down");
    }

    @Test
    @DisplayName("should throw PublishFailedException when the broker does not answer in time")
    void shouldFailOnTimeout() {
        when(kafkaTemplate.send("inventory.stock-reserved", "key-1", "{\"a\":1}"))
                .thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.publish(MESSAGE)).isInstanceOf(PublishFailedException.class);
    }

    @Test
    @DisplayName("should throw PublishFailedException when send itself throws")
    void shouldFailWhenSendThrows() {
        when(kafkaTemplate.send("inventory.stock-reserved", "key-1", "{\"a\":1}"))
                .thenThrow(new KafkaException("metadata timeout"));

        assertThatThrownBy(() -> publisher.publish(MESSAGE))
                .isInstanceOf(PublishFailedException.class)
                .hasCauseInstanceOf(KafkaException.class);
    }

    static InventoryProperties properties(Duration publishTimeout) {
        return new InventoryProperties(
                new InventoryProperties.Kafka(
                        new InventoryProperties.Topics(
                                "orders.order-placed", "inventory.stock-reserved", "inventory.stock-rejected"),
                        new InventoryProperties.ConsumerRetry(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10)),
                        publishTimeout),
                new InventoryProperties.OutboundDeadLetter(Duration.ofSeconds(30)),
                new InventoryProperties.Stock(Duration.ofSeconds(5)));
    }
}
