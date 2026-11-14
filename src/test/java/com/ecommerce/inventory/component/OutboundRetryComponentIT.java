package com.ecommerce.inventory.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.support.AbstractComponentIT;
import com.ecommerce.inventory.support.TestKafkaClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The running retry worker on real PostgreSQL and Kafka (US-03). Component settings: interval 200 ms,
 * base delay 200 ms, max delay 1 s, 3 attempts.
 */
class OutboundRetryComponentIT extends AbstractComponentIT {

    @ParameterizedTest
    @ValueSource(strings = {"inventory.stock-reserved", "inventory.stock-rejected", "inventory.stock-released"})
    @DisplayName("should deliver a due dead letter unchanged and mark it RESOLVED")
    void shouldDeliverDueDeadLetter(String topic) {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String payload = payload(eventId, orderId);
        UUID id = givenDueOutboundDeadLetter(eventId, topic, key, payload);

        List<ConsumerRecord<String, String>> records =
                kafka().awaitRecordsForOrder(topic, orderId, 1, Duration.ofSeconds(5));

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(key);
        assertThat(TestKafkaClient.json(records.getFirst().value())).isEqualTo(TestKafkaClient.json(payload));
        await().atMost(Duration.ofSeconds(5)).until(() -> "RESOLVED".equals(status(id)));
        assertThat(count("outbound_dead_letters where resolved_at is not null")).isEqualTo(1);
    }

    @Test
    @DisplayName("should not publish a row that is not due yet, nor a RESOLVED or EXHAUSTED row")
    void shouldLeaveRowsThatAreNotDue() {
        UUID later = givenDueOutboundDeadLetter(UUID.randomUUID(), "inventory.stock-reserved", "k1", "{}");
        jdbc().update("update outbound_dead_letters set next_retry_at = now() + interval '1 hour' where id = ?", later);
        UUID resolved = givenDueOutboundDeadLetter(UUID.randomUUID(), "inventory.stock-reserved", "k2", "{}");
        jdbc().update(
                        "update outbound_dead_letters set status = 'RESOLVED', resolved_at = now() where id = ?",
                        resolved);
        UUID exhausted = givenDueOutboundDeadLetter(UUID.randomUUID(), "inventory.stock-reserved", "k3", "{}");
        jdbc().update("update outbound_dead_letters set status = 'EXHAUSTED' where id = ?", exhausted);

        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> true);

        verify(publisher(), never()).publish(any());
        assertThat(status(later)).isEqualTo("PENDING");
        assertThat(count("outbound_dead_letters where retry_count = 0")).isEqualTo(3);
    }

    @Test
    @DisplayName("should back off after each failure, exhaust after 3 attempts and deliver after manual recovery")
    void shouldBackOffExhaustAndRecover() {
        UUID eventId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String topic = "inventory.stock-reserved";
        doThrow(new PublishFailedException("broker down", null))
                .when(publisher())
                .publish(argThat(message -> message != null && eventId.equals(message.eventId())));
        UUID id = givenDueOutboundDeadLetter(eventId, topic, "key", payload(eventId, orderId));

        await().atMost(Duration.ofSeconds(5)).until(() -> retryCount(id) == 1);
        assertThat(nextRetryIsInTheFuture(id)).isTrue();
        assertThat(jdbc().queryForObject("select last_error from outbound_dead_letters where id = ?", String.class, id))
                .contains("broker down");
        await().atMost(Duration.ofSeconds(5)).until(() -> retryCount(id) == 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> "EXHAUSTED".equals(status(id)));
        assertThat(retryCount(id)).isEqualTo(3);

        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).until(() -> true);
        verify(publisher(), times(3)).publish(argThat(message -> message != null && eventId.equals(message.eventId())));

        doAnswer(invocation -> invocation.callRealMethod()).when(publisher()).publish(any());
        jdbc().update(
                        "update outbound_dead_letters set status = 'PENDING', next_retry_at = now(), retry_count = 0"
                                + " where id = ?",
                        id);
        kafka().awaitRecordsForOrder(topic, orderId, 1, Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).until(() -> "RESOLVED".equals(status(id)));
    }

    @Test
    @DisplayName("should keep handling orders within 2 seconds while the worker keeps failing (FR-013)")
    void shouldNotSlowOrderHandlingWhileFailing() {
        UUID failingEvent = UUID.randomUUID();
        doThrow(new PublishFailedException("broker down", null))
                .when(publisher())
                .publish(argThat(message -> message != null && failingEvent.equals(message.eventId())));
        UUID id = givenDueOutboundDeadLetter(
                failingEvent, "inventory.stock-reserved", "key", payload(failingEvent, UUID.randomUUID()));
        await().atMost(Duration.ofSeconds(5)).until(() -> retryCount(id) >= 1);
        stock().given("SKU-001", 10);
        UUID orderId = UUID.randomUUID();

        kafka().send(
                        "orders.order-placed",
                        orderId.toString(),
                        orderPlaced(UUID.randomUUID(), orderId, line("SKU-001", 1)));

        assertThat(kafka().awaitRecordsForOrder("inventory.stock-reserved", orderId, 1, Duration.ofSeconds(2)))
                .hasSize(1);
    }

    @Test
    @DisplayName("should never run two rounds at the same time, even when a publish outlasts the interval")
    void shouldNotOverlapRounds() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        doAnswer(invocation -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    try {
                        return invocation.callRealMethod();
                    } finally {
                        running.decrementAndGet();
                    }
                })
                .when(outboundRetryService())
                .retryDueBatch();
        doAnswer(invocation -> {
                    Thread.sleep(500);
                    return invocation.callRealMethod();
                })
                .when(publisher())
                .publish(any(OutboundMessage.class));
        for (int i = 0; i < 3; i++) {
            UUID eventId = UUID.randomUUID();
            givenDueOutboundDeadLetter(
                    eventId, "inventory.stock-released", "k" + i, payload(eventId, UUID.randomUUID()));
        }

        await().atMost(Duration.ofSeconds(10))
                .until(() -> count("outbound_dead_letters where status = 'RESOLVED'") == 3);

        verify(outboundRetryService(), atLeastOnce()).retryDueBatch();
        assertThat(maxRunning.get()).isEqualTo(1);
    }

    private String status(UUID id) {
        return jdbc().queryForObject("select status from outbound_dead_letters where id = ?", String.class, id);
    }

    private int retryCount(UUID id) {
        Integer value =
                jdbc().queryForObject("select retry_count from outbound_dead_letters where id = ?", Integer.class, id);
        return value == null ? -1 : value;
    }

    private boolean nextRetryIsInTheFuture(UUID id) {
        Map<String, Object> row = jdbc().queryForMap(
                        "select next_retry_at > updated_at as later from outbound_dead_letters where id = ?", id);
        return Boolean.TRUE.equals(row.get("later"));
    }

    private static String payload(UUID eventId, UUID orderId) {
        return "{\"eventId\":\"" + eventId + "\",\"occurredAt\":\"2026-11-14T09:00:00.000Z\"," + "\"aggregateId\":\""
                + UUID.randomUUID() + "\",\"version\":1,\"orderId\":\"" + orderId + "\"}";
    }
}
