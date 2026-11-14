package com.ecommerce.inventory.messaging;

import com.ecommerce.inventory.application.ReleaseStockCommand;
import com.ecommerce.inventory.application.ReleaseStockService;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.messaging.dto.OrderCancelledMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code orders.order-cancelled}: parses and validates the message, hands it to
 * {@link ReleaseStockService}, and acknowledges the offset only after the service returns, that is after
 * the database commit (constitution Principle III). No business logic here; failures propagate to the
 * shared container error handler (retry with back-off, then the consumed-message dead-letter table).
 */
@Component
public class OrderCancelledListener {

    /** Listener container id, used by {@code KafkaListenerStarter} and in logs. */
    public static final String LISTENER_ID = "order-cancelled-listener";

    private final OrderCancelledMessageParser parser;
    private final ReleaseStockService service;

    /**
     * Creates the listener.
     *
     * @param parser parser and validator of raw messages
     * @param service the release-stock use case
     */
    public OrderCancelledListener(OrderCancelledMessageParser parser, ReleaseStockService service) {
        this.parser = parser;
        this.service = service;
    }

    /**
     * Handles one {@code orders.order-cancelled} record.
     *
     * @param consumerRecord the raw record
     * @param acknowledgment commits the offset when called
     */
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${inventory.kafka.topics.order-cancelled}")
    public void onMessage(ConsumerRecord<String, String> consumerRecord, Acknowledgment acknowledgment) {
        OrderCancelledMessage message = parser.parse(consumerRecord.value());
        service.release(
                new ReleaseStockCommand(message.eventId(), consumerRecord.topic(), new OrderId(message.aggregateId())));
        acknowledgment.acknowledge();
    }
}
