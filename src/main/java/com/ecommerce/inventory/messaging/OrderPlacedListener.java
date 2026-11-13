package com.ecommerce.inventory.messaging;

import com.ecommerce.inventory.application.ReserveStockCommand;
import com.ecommerce.inventory.application.ReserveStockService;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.messaging.dto.OrderPlacedMessage;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code orders.order-placed}: parses and validates the message, hands it to
 * {@link ReserveStockService}, and acknowledges the offset only after the service returns, that is
 * after the database commit (constitution Principle III). No business logic here; failures propagate
 * to the container error handler.
 */
@Component
public class OrderPlacedListener {

    /** Listener container id, used by {@code KafkaListenerStarter} and in logs. */
    public static final String LISTENER_ID = "order-placed-listener";

    private final OrderPlacedMessageParser parser;
    private final ReserveStockService service;

    /**
     * Creates the listener.
     *
     * @param parser parser and validator of raw messages
     * @param service the reserve-stock use case
     */
    public OrderPlacedListener(OrderPlacedMessageParser parser, ReserveStockService service) {
        this.parser = parser;
        this.service = service;
    }

    /**
     * Handles one {@code orders.order-placed} record.
     *
     * @param consumerRecord the raw record
     * @param acknowledgment commits the offset when called
     */
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "${inventory.kafka.topics.order-placed}")
    public void onMessage(ConsumerRecord<String, String> consumerRecord, Acknowledgment acknowledgment) {
        OrderPlacedMessage message = parser.parse(consumerRecord.value());
        service.reserve(toCommand(message, consumerRecord.topic()));
        acknowledgment.acknowledge();
    }

    private static ReserveStockCommand toCommand(OrderPlacedMessage message, String topic) {
        return new ReserveStockCommand(
                message.eventId(),
                topic,
                new OrderId(message.aggregateId()),
                message.items().stream()
                        .map(item -> new RequestedItem(new ProductId(item.productId()), new Quantity(item.quantity())))
                        .toList());
    }
}
