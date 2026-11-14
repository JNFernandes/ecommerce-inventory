package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.domain.model.OrderId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutcomePublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:15:30.456Z");
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final OutboundMessage MESSAGE =
            new OutboundMessage(UUID.randomUUID(), "inventory.stock-released", "key", "{}");

    @Mock
    private OutboundMessagePublisher publisher;

    @Mock
    private OutboundDeadLetterRepository outboundDeadLetters;

    private OutcomePublisher outcomePublisher;

    @BeforeEach
    void setUp() {
        outcomePublisher = new OutcomePublisher(
                publisher,
                outboundDeadLetters,
                new DeadLetterPolicy(FIRST_RETRY_DELAY),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should publish and not dead-letter anything when publishing succeeds")
    void shouldPublish() {
        outcomePublisher.publish(MESSAGE, ORDER_ID);

        verify(publisher).publish(MESSAGE);
        verify(outboundDeadLetters, never()).recordFailure(any(), any(), any());
    }

    @Test
    @DisplayName("should store the message as an outbound dead letter with the error and next retry time")
    void shouldDeadLetterFailedPublish() {
        doThrow(new PublishFailedException("Could not publish", new IllegalStateException("broker down")))
                .when(publisher)
                .publish(MESSAGE);

        assertThatCode(() -> outcomePublisher.publish(MESSAGE, ORDER_ID)).doesNotThrowAnyException();

        verify(outboundDeadLetters)
                .recordFailure(
                        eq(MESSAGE),
                        argThat(error -> error.contains("Could not publish") && error.contains("broker down")),
                        eq(NOW.plus(FIRST_RETRY_DELAY)));
    }

    @Test
    @DisplayName("should log and not throw when storing the outbound dead letter also fails")
    void shouldNotThrowWhenDeadLetterStoreFails() {
        doThrow(new PublishFailedException("Could not publish", new IllegalStateException("broker down")))
                .when(publisher)
                .publish(MESSAGE);
        doThrow(new IllegalStateException("database down"))
                .when(outboundDeadLetters)
                .recordFailure(any(), any(), any());

        assertThatCode(() -> outcomePublisher.publish(MESSAGE, ORDER_ID)).doesNotThrowAnyException();
    }
}
