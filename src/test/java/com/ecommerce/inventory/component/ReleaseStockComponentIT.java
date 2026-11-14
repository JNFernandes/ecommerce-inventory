package com.ecommerce.inventory.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.support.AbstractComponentIT;
import com.ecommerce.inventory.support.TestKafkaClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Whole release flow on real PostgreSQL and Kafka (US-02). */
class ReleaseStockComponentIT extends AbstractComponentIT {

    private static final String ORDER_PLACED = "orders.order-placed";
    private static final String ORDER_CANCELLED = "orders.order-cancelled";
    private static final String STOCK_RESERVED = "inventory.stock-reserved";
    private static final String STOCK_REJECTED = "inventory.stock-rejected";
    private static final String STOCK_RELEASED = "inventory.stock-released";
    private static final Duration WAIT = Duration.ofSeconds(10);

    // ---- Story 1: release -------------------------------------------------------------------------

    @Test
    @DisplayName("should give the stock back and publish stock-released within 2 seconds (SC-001)")
    void shouldReleaseAndPublish() {
        stock().given("SKU-001", 10);
        stock().given("SKU-002", 25);
        UUID orderId = placeAndAwaitReservation(line("SKU-001", 3), line("SKU-002", 5));
        assertThat(stock().available("SKU-001")).contains(7);

        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(UUID.randomUUID(), orderId));
        List<ConsumerRecord<String, String>> records =
                kafka().awaitRecordsForOrder(STOCK_RELEASED, orderId, 1, Duration.ofSeconds(2));

        ConsumerRecord<String, String> published = records.getFirst();
        JsonNode payload = TestKafkaClient.json(published.value());
        Map<String, Object> reservation =
                jdbc().queryForMap("select id, status, released_at from reservations where order_id = ?", orderId);
        assertThat(reservation.get("status")).isEqualTo("RELEASED");
        assertThat(reservation.get("released_at")).isNotNull();
        assertThat(published.key()).isEqualTo(reservation.get("id").toString());
        assertThat(payload.get("aggregateId").asText())
                .isEqualTo(reservation.get("id").toString());
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId.toString());
        List<String> fields = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .containsExactly("eventId", "occurredAt", "aggregateId", "version", "orderId", "items", "releasedAt");
        assertThat(items(payload)).containsExactly("SKU-001:3", "SKU-002:5");
        assertThat(stock().available("SKU-001")).contains(10);
        assertThat(stock().available("SKU-002")).contains(25);
        assertThat(count("processed_events where outcome = 'STOCK_RELEASED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("should give back the consolidated quantity of duplicate lines")
    void shouldReleaseConsolidatedQuantity() {
        stock().given("SKU-001", 10);
        UUID orderId = placeAndAwaitReservation(line("SKU-001", 2), line("SKU-001", 3));

        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(UUID.randomUUID(), orderId));
        JsonNode payload = TestKafkaClient.json(kafka().awaitRecordsForOrder(STOCK_RELEASED, orderId, 1, WAIT)
                .getFirst()
                .value());

        assertThat(items(payload)).containsExactly("SKU-001:5");
        assertThat(stock().available("SKU-001")).contains(10);
    }

    @Test
    @DisplayName("should not affect another order's reservation of the same product")
    void shouldNotAffectOtherReservations() {
        stock().given("SKU-001", 10);
        UUID cancelled = placeAndAwaitReservation(line("SKU-001", 3));
        UUID kept = placeAndAwaitReservation(line("SKU-001", 4));

        kafka().send(ORDER_CANCELLED, cancelled.toString(), orderCancelled(UUID.randomUUID(), cancelled));
        kafka().awaitRecordsForOrder(STOCK_RELEASED, cancelled, 1, WAIT);

        assertThat(stock().available("SKU-001")).contains(6);
        assertThat(status(kept)).isEqualTo("RESERVED");
    }

    // ---- Story 2: nothing to give back ------------------------------------------------------------

    @Test
    @DisplayName("should ignore the cancellation of a rejected order without retrying or dead-lettering")
    void shouldIgnoreRejectedOrder() {
        stock().given("SKU-005", 0);
        UUID orderId = UUID.randomUUID();
        kafka().send(ORDER_PLACED, orderId.toString(), orderPlaced(UUID.randomUUID(), orderId, line("SKU-005", 1)));
        kafka().awaitRecordsForOrder(STOCK_REJECTED, orderId, 1, WAIT);

        UUID cancel = UUID.randomUUID();
        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(cancel, orderId));
        awaitProcessed(cancel, "CANCELLATION_IGNORED");

        assertThat(kafka().recordsForOrder(STOCK_RELEASED, orderId)).isEmpty();
        assertThat(stock().available("SKU-005")).contains(0);
        assertThat(status(orderId)).isEqualTo("REJECTED");
        assertThat(count("consumed_dead_letters")).isZero();
        verify(releaseStockService(), times(1))
                .release(argThat(command -> command.orderId().value().equals(orderId)));
    }

    @Test
    @DisplayName("should give stock back only once when an order is cancelled again with a new event")
    void shouldIgnoreSecondCancellation() {
        stock().given("SKU-001", 10);
        UUID orderId = placeAndAwaitReservation(line("SKU-001", 3));
        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(UUID.randomUUID(), orderId));
        kafka().awaitRecordsForOrder(STOCK_RELEASED, orderId, 1, WAIT);

        UUID second = UUID.randomUUID();
        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(second, orderId));
        awaitProcessed(second, "CANCELLATION_IGNORED");

        assertThat(kafka().recordsForOrder(STOCK_RELEASED, orderId)).hasSize(1);
        assertThat(stock().available("SKU-001")).contains(10);
        assertThat(count("consumed_dead_letters")).isZero();
    }

    // ---- Story 3: cancellation before placement ---------------------------------------------------

    @Test
    @DisplayName("should reserve nothing for a placement that arrives after the order was cancelled")
    void shouldIgnorePlacementAfterEarlyCancellation() {
        stock().given("SKU-001", 10);
        UUID orderId = UUID.randomUUID();
        UUID cancel = UUID.randomUUID();
        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(cancel, orderId));
        awaitProcessed(cancel, "CANCELLATION_RECORDED");
        assertThat(count("cancellation_markers where order_id = '" + orderId + "'"))
                .isEqualTo(1);

        UUID place = UUID.randomUUID();
        kafka().send(ORDER_PLACED, orderId.toString(), orderPlaced(place, orderId, line("SKU-001", 1)));
        awaitProcessed(place, "PLACEMENT_CANCELLED");

        assertThat(count("reservations where order_id = '" + orderId + "'")).isZero();
        assertThat(stock().available("SKU-001")).contains(10);
        assertThat(kafka().recordsForOrder(STOCK_RESERVED, orderId)).isEmpty();
        assertThat(kafka().recordsForOrder(STOCK_REJECTED, orderId)).isEmpty();
        assertThat(kafka().recordsForOrder(STOCK_RELEASED, orderId)).isEmpty();
        assertThat(count("consumed_dead_letters")).isZero();
        verify(reserveStockService(), times(1))
                .reserve(argThat(command -> command.orderId().value().equals(orderId)));
    }

    @Test
    @DisplayName("should keep one marker when an order is cancelled twice before its placement")
    void shouldKeepOneMarkerForDuplicateEarlyCancellation() {
        UUID orderId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(first, orderId));
        awaitProcessed(first, "CANCELLATION_RECORDED");
        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(second, orderId));
        awaitProcessed(second, "CANCELLATION_IGNORED");

        assertThat(count("cancellation_markers where order_id = '" + orderId + "'"))
                .isEqualTo(1);
    }

    // ---- Story 4: redelivery ----------------------------------------------------------------------

    @Test
    @DisplayName("should give stock back once and republish the same message when a cancellation is redelivered")
    void shouldHandleRedeliveredCancellation() {
        stock().given("SKU-001", 10);
        UUID orderId = placeAndAwaitReservation(line("SKU-001", 3));
        String cancellation = orderCancelled(UUID.randomUUID(), orderId);

        kafka().send(ORDER_CANCELLED, orderId.toString(), cancellation);
        kafka().awaitRecordsForOrder(STOCK_RELEASED, orderId, 1, WAIT);
        kafka().send(ORDER_CANCELLED, orderId.toString(), cancellation);
        List<ConsumerRecord<String, String>> records = kafka().awaitRecordsForOrder(STOCK_RELEASED, orderId, 2, WAIT);

        assertThat(TestKafkaClient.json(records.get(1).value()))
                .isEqualTo(TestKafkaClient.json(records.get(0).value()));
        assertThat(records.get(1).key()).isEqualTo(records.get(0).key());
        assertThat(stock().available("SKU-001")).contains(10);
        assertThat(count("processed_events where outcome = 'STOCK_RELEASED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("should keep one marker and publish nothing when an early cancellation is redelivered")
    void shouldHandleRedeliveredEarlyCancellation() {
        UUID orderId = UUID.randomUUID();
        UUID cancel = UUID.randomUUID();
        String cancellation = orderCancelled(cancel, orderId);

        kafka().send(ORDER_CANCELLED, orderId.toString(), cancellation);
        awaitProcessed(cancel, "CANCELLATION_RECORDED");
        kafka().send(ORDER_CANCELLED, orderId.toString(), cancellation);
        await().atMost(WAIT)
                .untilAsserted(() -> verify(releaseStockService(), times(2))
                        .release(argThat(command -> command.orderId().value().equals(orderId))));

        assertThat(count("cancellation_markers where order_id = '" + orderId + "'"))
                .isEqualTo(1);
        assertThat(count("processed_events where event_id = '" + cancel + "'")).isEqualTo(1);
        assertThat(kafka().recordsForOrder(STOCK_RELEASED, orderId)).isEmpty();
    }

    // ---- Story 5: failures --------------------------------------------------------------------------

    @Test
    @DisplayName("should dead-letter an invalid cancellation on the first attempt and process the next one")
    void shouldDeadLetterInvalidCancellation() {
        UUID orderId = UUID.randomUUID();
        UUID cancel = UUID.randomUUID();
        String key = orderId.toString();

        RecordMetadata invalid = kafka().send(ORDER_CANCELLED, key, "not json");
        kafka().send(ORDER_CANCELLED, key, orderCancelled(cancel, orderId));
        awaitProcessed(cancel, "CANCELLATION_RECORDED");

        Map<String, Object> deadLetter = jdbc().queryForMap(
                        "select * from consumed_dead_letters where topic = ? and partition = ? and record_offset = ?",
                        ORDER_CANCELLED,
                        invalid.partition(),
                        invalid.offset());
        assertThat(deadLetter.get("attempts")).isEqualTo(1);
        assertThat(deadLetter.get("payload")).isEqualTo("not json");
    }

    @Test
    @DisplayName("should keep the release and store a PENDING outbound dead letter when publishing fails")
    void shouldDeadLetterFailedReleasePublish() {
        stock().given("SKU-001", 10);
        UUID orderId = placeAndAwaitReservation(line("SKU-001", 3));
        doThrow(new PublishFailedException("Could not publish", new IllegalStateException("broker down")))
                .when(publisher())
                .publish(argThat(message -> message.topic().equals(STOCK_RELEASED)
                        && message.payload().contains(orderId.toString())));

        kafka().send(ORDER_CANCELLED, orderId.toString(), orderCancelled(UUID.randomUUID(), orderId));
        await().atMost(WAIT).until(() -> count("outbound_dead_letters") == 1);

        Map<String, Object> deadLetter = jdbc().queryForMap("select * from outbound_dead_letters");
        assertThat(deadLetter.get("status")).isEqualTo("PENDING");
        assertThat(deadLetter.get("topic")).isEqualTo(STOCK_RELEASED);
        assertThat(status(orderId)).isEqualTo("RELEASED");
        assertThat(stock().available("SKU-001")).contains(10);
        assertThat(kafka().recordsForOrder(STOCK_RELEASED, orderId)).isEmpty();
    }

    @Test
    @DisplayName("should dead-letter a cancellation that keeps failing and process the next one")
    void shouldDeadLetterPersistentFailure() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        UUID nextCancel = UUID.randomUUID();
        doThrow(new IllegalStateException("database down"))
                .when(releaseStockService())
                .release(argThat(command -> command.orderId().value().equals(failing)));

        RecordMetadata failed =
                kafka().send(ORDER_CANCELLED, failing.toString(), orderCancelled(UUID.randomUUID(), failing));
        kafka().send(ORDER_CANCELLED, failing.toString(), orderCancelled(nextCancel, next));
        awaitProcessed(nextCancel, "CANCELLATION_RECORDED");

        Map<String, Object> deadLetter = jdbc().queryForMap(
                        "select * from consumed_dead_letters where topic = ? and partition = ? and record_offset = ?",
                        ORDER_CANCELLED,
                        failed.partition(),
                        failed.offset());
        assertThat(deadLetter.get("attempts")).isEqualTo(3);
        assertThat((String) deadLetter.get("error")).contains("database down");
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private UUID placeAndAwaitReservation(String... lines) {
        UUID orderId = UUID.randomUUID();
        kafka().send(ORDER_PLACED, orderId.toString(), orderPlaced(UUID.randomUUID(), orderId, lines));
        kafka().awaitRecordsForOrder(STOCK_RESERVED, orderId, 1, WAIT);
        return orderId;
    }

    private void awaitProcessed(UUID eventId, String outcome) {
        await().atMost(WAIT)
                .until(() ->
                        count("processed_events where event_id = '" + eventId + "' and outcome = '" + outcome + "'")
                                == 1);
    }

    private String status(UUID orderId) {
        return jdbc().queryForObject("select status from reservations where order_id = ?", String.class, orderId);
    }

    private static List<String> items(JsonNode payload) {
        List<String> items = new ArrayList<>();
        payload.get("items")
                .forEach(item -> items.add(item.get("productId").asText() + ":"
                        + item.get("quantity").asLong()));
        return items;
    }
}
