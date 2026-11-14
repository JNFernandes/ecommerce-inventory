package com.ecommerce.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.ReleaseStockCommand;
import com.ecommerce.inventory.application.ReleaseStockOutcome;
import com.ecommerce.inventory.application.ReleaseStockService;
import com.ecommerce.inventory.domain.model.OrderId;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class OrderCancelledListenerTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final String TOPIC = "orders.order-cancelled";
    private static final String EVENT_ID = "2c9d8e7f-6a5b-4c3d-9e8f-7a6b5c4d3e2f";
    private static final String ORDER_ID = "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

    @Mock
    private ReleaseStockService service;

    @Mock
    private Acknowledgment acknowledgment;

    private OrderCancelledListener listener;

    @AfterAll
    static void close() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setUp() {
        listener = new OrderCancelledListener(
                new OrderCancelledMessageParser(new StrictJsonParser(VALIDATORS.getValidator())), service);
    }

    @Test
    @DisplayName("should map the message to a command and acknowledge after the service returns")
    void shouldMapAndAcknowledgeAfterService() {
        ReleaseStockCommand expected =
                new ReleaseStockCommand(UUID.fromString(EVENT_ID), TOPIC, new OrderId(UUID.fromString(ORDER_ID)));
        when(service.release(expected)).thenReturn(new ReleaseStockOutcome.CancellationRecorded());

        listener.onMessage(record(validMessage()), acknowledgment);

        InOrder order = inOrder(service, acknowledgment);
        order.verify(service).release(expected);
        order.verify(acknowledgment).acknowledge();
    }

    @Test
    @DisplayName("should not acknowledge when the service throws")
    void shouldNotAcknowledgeOnFailure() {
        when(service.release(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.onMessage(record(validMessage()), acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("should rethrow InvalidMessageException without calling the service for an invalid message")
    void shouldRejectInvalidMessage() {
        assertThatThrownBy(() -> listener.onMessage(record("not json"), acknowledgment))
                .isInstanceOf(InvalidMessageException.class);
        verifyNoInteractions(service);
        verify(acknowledgment, never()).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 7L, ORDER_ID, value);
    }

    private static String validMessage() {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-11-13T10:20:00.000Z\"," + "\"aggregateId\":\""
                + ORDER_ID + "\",\"version\":1,\"reason\":\"payment failed\"}";
    }
}
