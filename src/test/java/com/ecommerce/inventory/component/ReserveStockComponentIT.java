package com.ecommerce.inventory.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ecommerce.inventory.messaging.OrderPlacedListener;
import com.ecommerce.inventory.support.AbstractComponentIT;
import com.ecommerce.inventory.support.TestKafkaClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/** Whole flow on real PostgreSQL and Kafka: order-placed consumed, stock reserved, outcome published. */
class ReserveStockComponentIT extends AbstractComponentIT {

    private static final String ORDER_PLACED = "orders.order-placed";
    private static final String STOCK_RESERVED = "inventory.stock-reserved";
    private static final String STOCK_REJECTED = "inventory.stock-rejected";

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @BeforeEach
    void waitForListenerAssignment() {
        MessageListenerContainer container = listeners.getListenerContainer(OrderPlacedListener.LISTENER_ID);
        assertThat(container).isNotNull();
        await().atMost(Duration.ofSeconds(60))
                .until(() -> container.getAssignedPartitions() != null
                        && !container.getAssignedPartitions().isEmpty());
    }

    @Test
    @DisplayName("should reserve every item and publish stock-reserved within 2 seconds (SC-001)")
    void shouldReserveAndPublish() {
        stock().given("SKU-001", 10);
        stock().given("SKU-002", 25);
        UUID orderId = UUID.randomUUID();

        kafka().send(
                        ORDER_PLACED,
                        orderId.toString(),
                        orderPlaced(UUID.randomUUID(), orderId, line("SKU-001", 3), line("SKU-002", 5)));
        List<ConsumerRecord<String, String>> records =
                kafka().awaitRecordsForOrder(STOCK_RESERVED, orderId, 1, Duration.ofSeconds(2));

        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> published = records.getFirst();
        JsonNode payload = TestKafkaClient.json(published.value());
        UUID reservationId = jdbc().queryForObject(
                        "select id from reservations where order_id = ? and status = 'RESERVED'", UUID.class, orderId);
        assertThat(published.key()).isEqualTo(reservationId.toString());
        assertThat(payload.get("aggregateId").asText()).isEqualTo(reservationId.toString());
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
        assertThat(quantities(payload)).containsExactly(Map.entry("SKU-001", 3L), Map.entry("SKU-002", 5L));
        assertThat(stock().available("SKU-001")).contains(7);
        assertThat(stock().available("SKU-002")).contains(20);
        String storedPayload = jdbc().queryForObject(
                        "select payload::text from outcome_messages where reservation_id = ?",
                        String.class,
                        reservationId);
        assertThat(TestKafkaClient.json(storedPayload)).isEqualTo(payload);
        assertThat(count("processed_events")).isEqualTo(1);
    }

    @Test
    @DisplayName("should consolidate duplicate lines into one reserved item")
    void shouldConsolidateDuplicateLines() {
        stock().given("SKU-001", 10);
        UUID orderId = UUID.randomUUID();

        kafka().send(
                        ORDER_PLACED,
                        orderId.toString(),
                        orderPlaced(UUID.randomUUID(), orderId, line("SKU-001", 2), line("SKU-001", 3)));
        JsonNode payload =
                TestKafkaClient.json(kafka().awaitRecordsForOrder(STOCK_RESERVED, orderId, 1, Duration.ofSeconds(10))
                        .getFirst()
                        .value());

        assertThat(quantities(payload)).containsExactly(Map.entry("SKU-001", 5L));
        assertThat(stock().available("SKU-001")).contains(5);
    }

    @Test
    @DisplayName("should reserve the exact available quantity, leaving zero")
    void shouldReserveExactStock() {
        stock().given("SKU-004", 5);
        UUID orderId = UUID.randomUUID();

        kafka().send(ORDER_PLACED, orderId.toString(), orderPlaced(UUID.randomUUID(), orderId, line("SKU-004", 5)));
        kafka().awaitRecordsForOrder(STOCK_RESERVED, orderId, 1, Duration.ofSeconds(10));

        assertThat(stock().available("SKU-004")).contains(0);
    }

    @Test
    @DisplayName("should reject with insufficient stock, list only the failing item and keep stock unchanged")
    void shouldRejectInsufficientStock() {
        stock().given("SKU-001", 10);
        stock().given("SKU-004", 5);

        JsonNode payload = placeAndAwaitRejection(line("SKU-001", 2), line("SKU-004", 6));

        assertThat(payload.get("reason").asText()).isEqualTo("insufficient stock");
        assertThat(failures(payload)).containsExactly("SKU-004:6:5");
        assertThat(stock().available("SKU-001")).contains(10);
        assertThat(stock().available("SKU-004")).contains(5);
    }

    @Test
    @DisplayName("should reject a product with zero stock as insufficient stock")
    void shouldRejectZeroStock() {
        stock().given("SKU-005", 0);

        JsonNode payload = placeAndAwaitRejection(line("SKU-005", 1));

        assertThat(payload.get("reason").asText()).isEqualTo("insufficient stock");
        assertThat(failures(payload)).containsExactly("SKU-005:1:0");
    }

    @Test
    @DisplayName("should reject an unknown product with available 0 and keep the known product unchanged")
    void shouldRejectUnknownProduct() {
        stock().given("SKU-001", 10);

        JsonNode payload = placeAndAwaitRejection(line("SKU-001", 1), line("SKU-999", 2));

        assertThat(payload.get("reason").asText()).isEqualTo("unknown product");
        assertThat(failures(payload)).containsExactly("SKU-999:2:0");
        assertThat(stock().available("SKU-001")).contains(10);
    }

    @Test
    @DisplayName("should reject with the combined reason when unknown and insufficient items are mixed")
    void shouldRejectMixedFailures() {
        stock().given("SKU-004", 5);

        JsonNode payload = placeAndAwaitRejection(line("SKU-004", 6), line("SKU-999", 1));

        assertThat(payload.get("reason").asText()).isEqualTo("unknown product and insufficient stock");
        assertThat(failures(payload)).containsExactly("SKU-004:6:5", "SKU-999:1:0");
        assertThat(stock().available("SKU-004")).contains(5);
    }

    @Test
    @DisplayName("should reject when the consolidated quantity exceeds the available stock")
    void shouldRejectConsolidatedOverRequest() {
        stock().given("SKU-001", 10);

        JsonNode payload = placeAndAwaitRejection(line("SKU-001", 6), line("SKU-001", 7));

        assertThat(failures(payload)).containsExactly("SKU-001:13:10");
        assertThat(stock().available("SKU-001")).contains(10);
    }

    /**
     * Places an order, waits for its stock-rejected message and checks what every rejection shares:
     * one REJECTED reservation, key = aggregateId = reservation id, exact field set, no dead letter,
     * and a single service invocation (rejections are never retried, FR-025).
     */
    private JsonNode placeAndAwaitRejection(String... lines) {
        UUID orderId = UUID.randomUUID();
        kafka().send(ORDER_PLACED, orderId.toString(), orderPlaced(UUID.randomUUID(), orderId, lines));

        ConsumerRecord<String, String> published = kafka().awaitRecordsForOrder(
                        STOCK_REJECTED, orderId, 1, Duration.ofSeconds(10))
                .getFirst();
        JsonNode payload = TestKafkaClient.json(published.value());
        UUID reservationId = jdbc().queryForObject(
                        "select id from reservations where order_id = ? and status = 'REJECTED'", UUID.class, orderId);
        assertThat(published.key()).isEqualTo(reservationId.toString());
        assertThat(payload.get("aggregateId").asText()).isEqualTo(reservationId.toString());
        List<String> fields = new java.util.ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactly(
                        "eventId", "occurredAt", "aggregateId", "version", "orderId", "reason", "items", "rejectedAt");
        assertThat(kafka().recordsForOrder(STOCK_RESERVED, orderId)).isEmpty();
        assertThat(count("consumed_dead_letters")).isZero();
        verify(reserveStockService(), times(1))
                .reserve(argThat(command -> command.orderId().value().equals(orderId)));
        return payload;
    }

    private static List<String> failures(JsonNode payload) {
        return java.util.stream.StreamSupport.stream(payload.get("items").spliterator(), false)
                .map(item -> item.get("productId").asText() + ":"
                        + item.get("requested").asLong() + ":"
                        + item.get("available").asInt())
                .toList();
    }

    private int count(String table) {
        Integer count = jdbc().queryForObject("select count(*) from " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static Map<String, Long> quantities(JsonNode payload) {
        return java.util.stream.StreamSupport.stream(payload.get("items").spliterator(), false)
                .collect(Collectors.toMap(
                        item -> item.get("productId").asText(),
                        item -> item.get("quantity").asLong(),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));
    }

    private static String line(String productId, int quantity) {
        return "{\"productId\":\"" + productId + "\",\"quantity\":" + quantity + ",\"unitPrice\":1.5}";
    }

    private static String orderPlaced(UUID eventId, UUID orderId, String... lines) {
        return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-11-13T10:15:30.123Z\","
                + "\"aggregateId\":\"" + orderId + "\",\"version\":1,"
                + "\"customerId\":\"" + UUID.randomUUID() + "\","
                + "\"items\":[" + String.join(",", lines) + "],\"totalAmount\":10}";
    }
}
