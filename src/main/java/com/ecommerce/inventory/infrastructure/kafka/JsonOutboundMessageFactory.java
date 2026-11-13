package com.ecommerce.inventory.infrastructure.kafka;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessageFactory;
import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.event.StockReserved;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import com.ecommerce.inventory.infrastructure.kafka.message.ReservedItem;
import com.ecommerce.inventory.infrastructure.kafka.message.StockReservedMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds the JSON messages of the {@code inventory.*} topics from domain events: new event id, key and
 * {@code aggregateId} = reservation id, version 1 (research R8).
 */
@Component
public class JsonOutboundMessageFactory implements OutboundMessageFactory {

    /** Schema version of every message this service produces. */
    public static final int VERSION = 1;

    private final ObjectMapper objectMapper;
    private final InventoryProperties.Topics topics;

    /**
     * Creates the factory.
     *
     * @param objectMapper Spring-configured JSON mapper (ISO 8601 timestamps)
     * @param properties service configuration (topic names)
     */
    public JsonOutboundMessageFactory(ObjectMapper objectMapper, InventoryProperties properties) {
        this.objectMapper = objectMapper;
        this.topics = properties.kafka().topics();
    }

    @Override
    public OutboundMessage create(InventoryDomainEvent event) {
        UUID eventId = UUID.randomUUID();
        String key = event.reservationId().toString();
        return switch (event) {
            case StockReserved reserved ->
                new OutboundMessage(eventId, topics.stockReserved(), key, toJson(reservedMessage(eventId, reserved)));
        };
    }

    private static StockReservedMessage reservedMessage(UUID eventId, StockReserved event) {
        return new StockReservedMessage(
                eventId,
                event.occurredAt(),
                event.reservationId().value(),
                VERSION,
                event.orderId().value(),
                event.items().stream()
                        .map(item -> new ReservedItem(
                                item.productId().value(), item.quantity().value()))
                        .toList(),
                event.reservedAt());
    }

    private String toJson(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Could not serialize " + message.getClass().getSimpleName(), e);
        }
    }
}
