package com.ecommerce.inventory.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.domain.event.StockRejected;
import com.ecommerce.inventory.domain.event.StockReserved;
import com.ecommerce.inventory.domain.model.FailureKind;
import com.ecommerce.inventory.domain.model.ItemFailure;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RejectionReason;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.support.TestKafkaClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonOutboundMessageFactoryTest {

    private static final ReservationId RESERVATION_ID = ReservationId.newId();
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final Instant RESERVED_AT = Instant.parse("2026-11-13T10:15:30.456Z");

    private final JsonOutboundMessageFactory factory = new JsonOutboundMessageFactory(
            JsonMapper.builder()
                    .addModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                    .build(),
            KafkaOutboundMessagePublisherTest.properties(Duration.ofSeconds(1)));

    @Test
    @DisplayName("should map StockReserved to inventory.stock-reserved keyed by the reservation id")
    void shouldMapStockReservedTopicAndKey() {
        OutboundMessage message = factory.create(reserved());

        assertThat(message.topic()).isEqualTo("inventory.stock-reserved");
        assertThat(message.key()).isEqualTo(RESERVATION_ID.toString());
    }

    @Test
    @DisplayName("should produce exactly the stock-reserved contract fields")
    void shouldProduceContractFields() {
        OutboundMessage message = factory.create(reserved());
        JsonNode json = TestKafkaClient.json(message.payload());

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactly("eventId", "occurredAt", "aggregateId", "version", "orderId", "items", "reservedAt");
        assertThat(json.get("eventId").asText()).isEqualTo(message.eventId().toString());
        assertThat(json.get("aggregateId").asText()).isEqualTo(RESERVATION_ID.toString());
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-11-13T10:15:30.456Z");
        assertThat(json.get("reservedAt").asText()).isEqualTo("2026-11-13T10:15:30.456Z");
        assertThat(json.get("items")).hasSize(2);
        assertThat(json.get("items").get(0).get("productId").asText()).isEqualTo("SKU-001");
        assertThat(json.get("items").get(0).get("quantity").asLong()).isEqualTo(3);
        List<String> itemFields = new ArrayList<>();
        json.get("items").get(0).fieldNames().forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactly("productId", "quantity");
    }

    @Test
    @DisplayName("should give every message a new event id")
    void shouldGenerateNewEventIds() {
        assertThat(factory.create(reserved()).eventId())
                .isNotEqualTo(factory.create(reserved()).eventId());
    }

    @Test
    @DisplayName("should map StockRejected to inventory.stock-rejected keyed by the reservation id")
    void shouldMapStockRejectedTopicAndKey() {
        OutboundMessage message = factory.create(rejected());

        assertThat(message.topic()).isEqualTo("inventory.stock-rejected");
        assertThat(message.key()).isEqualTo(RESERVATION_ID.toString());
    }

    @Test
    @DisplayName("should produce exactly the stock-rejected contract fields with only the failing items")
    void shouldProduceRejectedContractFields() {
        OutboundMessage message = factory.create(rejected());
        JsonNode json = TestKafkaClient.json(message.payload());

        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactly(
                        "eventId", "occurredAt", "aggregateId", "version", "orderId", "reason", "items", "rejectedAt");
        assertThat(json.get("aggregateId").asText()).isEqualTo(RESERVATION_ID.toString());
        assertThat(json.get("version").asInt()).isEqualTo(1);
        assertThat(json.get("orderId").asText()).isEqualTo(ORDER_ID.toString());
        assertThat(json.get("reason").asText()).isEqualTo("unknown product and insufficient stock");
        assertThat(json.get("occurredAt").asText()).isEqualTo("2026-11-13T10:15:30.456Z");
        assertThat(json.get("rejectedAt").asText()).isEqualTo("2026-11-13T10:15:30.456Z");
        assertThat(json.get("items")).hasSize(2);
        JsonNode first = json.get("items").get(0);
        List<String> itemFields = new ArrayList<>();
        first.fieldNames().forEachRemaining(itemFields::add);
        assertThat(itemFields).containsExactly("productId", "requested", "available");
        assertThat(first.get("productId").asText()).isEqualTo("SKU-004");
        assertThat(first.get("requested").asLong()).isEqualTo(6);
        assertThat(first.get("available").asInt()).isEqualTo(5);
        assertThat(json.get("items").get(1).get("available").asInt()).isZero();
    }

    private static StockRejected rejected() {
        return new StockRejected(
                RESERVATION_ID,
                ORDER_ID,
                RejectionReason.UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK,
                List.of(
                        new ItemFailure(new ProductId("SKU-004"), 6, 5, FailureKind.INSUFFICIENT_STOCK),
                        new ItemFailure(new ProductId("SKU-999"), 1, 0, FailureKind.UNKNOWN_PRODUCT)),
                RESERVED_AT);
    }

    private static StockReserved reserved() {
        return new StockReserved(
                RESERVATION_ID,
                ORDER_ID,
                List.of(
                        new RequestedItem(new ProductId("SKU-001"), new Quantity(3)),
                        new RequestedItem(new ProductId("SKU-002"), new Quantity(5))),
                RESERVED_AT);
    }
}
