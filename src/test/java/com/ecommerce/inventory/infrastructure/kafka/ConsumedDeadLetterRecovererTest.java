package com.ecommerce.inventory.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository;
import com.ecommerce.inventory.infrastructure.persistence.ConsumedDeadLetterRepository.ConsumedRecord;
import com.ecommerce.inventory.messaging.InvalidMessageException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

@ExtendWith(MockitoExtension.class)
class ConsumedDeadLetterRecovererTest {

    private static final ConsumerRecord<String, String> RECORD =
            new ConsumerRecord<>("orders.order-placed", 1, 42L, "key-1", "not json");

    @Mock
    private ConsumedDeadLetterRepository repository;

    private ConsumedDeadLetterRecoverer recoverer;

    @BeforeEach
    void setUp() {
        recoverer = new ConsumedDeadLetterRecoverer(repository, 3);
    }

    @Test
    @DisplayName("should store an invalid message verbatim with one attempt and its root cause")
    void shouldStoreInvalidMessageWithOneAttempt() {
        Exception failure = new ListenerExecutionFailedException(
                "Listener failed", new InvalidMessageException("Message is not valid JSON"));

        recoverer.accept(RECORD, failure);

        verify(repository)
                .record(
                        new ConsumedRecord("orders.order-placed", 1, 42L, "key-1", "not json"),
                        InvalidMessageException.class.getName() + ": Message is not valid JSON",
                        1);
    }

    @Test
    @DisplayName("should record max retries + 1 attempts for a failure that was retried")
    void shouldRecordRetriedAttempts() {
        Exception failure =
                new ListenerExecutionFailedException("Listener failed", new IllegalStateException("database down"));

        recoverer.accept(RECORD, failure);

        verify(repository).record(any(), eq(IllegalStateException.class.getName() + ": database down"), eq(4));
    }

    @Test
    @DisplayName("should store null key and value as null")
    void shouldHandleNullKeyAndValue() {
        recoverer.accept(
                new ConsumerRecord<String, String>("orders.order-placed", 0, 1L, null, null),
                new InvalidMessageException("Message value is empty"));

        verify(repository).record(eq(new ConsumedRecord("orders.order-placed", 0, 1L, null, null)), any(), anyInt());
    }

    @Test
    @DisplayName("should propagate a storage failure so the record is retried and never lost")
    void shouldPropagateStorageFailure() {
        doThrow(new IllegalStateException("database down")).when(repository).record(any(), any(), anyInt());

        assertThatThrownBy(() -> recoverer.accept(RECORD, new InvalidMessageException("bad")))
                .isInstanceOf(IllegalStateException.class);
    }
}
