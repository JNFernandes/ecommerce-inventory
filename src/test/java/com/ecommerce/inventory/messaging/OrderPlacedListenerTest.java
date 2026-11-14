package com.ecommerce.inventory.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.ReserveStockCommand;
import com.ecommerce.inventory.application.ReserveStockOutcome;
import com.ecommerce.inventory.application.ReserveStockService;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.domain.model.ReservationStatus;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class OrderPlacedListenerTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();
    private static final String TOPIC = "orders.order-placed";
    private static final String EVENT_ID = "8f0c6a1e-6a4b-4a43-9a1c-1f5f7f0a2b10";
    private static final String ORDER_ID = "3b2a1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d";

    @Mock
    private ReserveStockService service;

    @Mock
    private Acknowledgment acknowledgment;

    private OrderPlacedListener listener;

    @AfterAll
    static void close() {
        VALIDATORS.close();
    }

    @BeforeEach
    void setUp() {
        listener = new OrderPlacedListener(
                new OrderPlacedMessageParser(new StrictJsonParser(VALIDATORS.getValidator())), service);
    }

    @Test
    @DisplayName("should map the message to a command and acknowledge after the service returns")
    void shouldMapAndAcknowledgeAfterService() {
        when(service.reserve(any()))
                .thenReturn(new ReserveStockOutcome.Decided(ReservationId.newId(), ReservationStatus.RESERVED));
        ArgumentCaptor<ReserveStockCommand> command = ArgumentCaptor.forClass(ReserveStockCommand.class);

        listener.onMessage(record(validMessage()), acknowledgment);

        InOrder order = inOrder(service, acknowledgment);
        order.verify(service).reserve(command.capture());
        order.verify(acknowledgment).acknowledge();
        assertThat(command.getValue())
                .isEqualTo(new ReserveStockCommand(
                        UUID.fromString(EVENT_ID),
                        TOPIC,
                        new OrderId(UUID.fromString(ORDER_ID)),
                        java.util.List.of(
                                new RequestedItem(new ProductId("SKU-001"), new Quantity(3)),
                                new RequestedItem(new ProductId("SKU-001"), new Quantity(2)))));
    }

    @Test
    @DisplayName("should not acknowledge when the service throws")
    void shouldNotAcknowledgeOnFailure() {
        when(service.reserve(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> listener.onMessage(record(validMessage()), acknowledgment))
                .isInstanceOf(IllegalStateException.class);
        verify(acknowledgment, never()).acknowledge();
    }

    @Test
    @DisplayName("should rethrow InvalidMessageException without calling the service for an invalid message")
    void shouldRejectInvalidMessageWithoutCallingService() {
        assertThatThrownBy(() -> listener.onMessage(record("{\"not\":\"an order\"}"), acknowledgment))
                .isInstanceOf(InvalidMessageException.class);
        verifyNoInteractions(service);
        verify(acknowledgment, never()).acknowledge();
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>(TOPIC, 0, 42L, ORDER_ID, value);
    }

    private static String validMessage() {
        return "{\"eventId\":\"" + EVENT_ID + "\",\"occurredAt\":\"2026-11-13T10:15:30.123Z\","
                + "\"aggregateId\":\"" + ORDER_ID + "\",\"version\":1,"
                + "\"items\":[{\"productId\":\"SKU-001\",\"quantity\":3,\"unitPrice\":19.99},"
                + "{\"productId\":\"SKU-001\",\"quantity\":2,\"unitPrice\":19.99}],\"totalAmount\":99.95}";
    }
}
