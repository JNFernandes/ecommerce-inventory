package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutcomeMessageRepository;
import com.ecommerce.inventory.application.port.ProcessedEvent;
import com.ecommerce.inventory.application.port.ProcessedEventRepository;
import com.ecommerce.inventory.application.port.ProcessedOutcome;
import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import com.ecommerce.inventory.support.TestKafkaClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class OutcomeAndProcessedEventAdaptersIT extends AbstractPostgresIT {

    @Autowired
    private OutcomeMessageRepository outcomeMessages;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private ReservationId reservationId;

    @BeforeEach
    void arrange() {
        DatabaseCleaner.truncateAll(jdbc);
        reservationId = ReservationId.newId();
        jdbc.update(
                "insert into reservations (id, order_id, status, created_at, updated_at)"
                        + " values (?, ?, 'RESERVED', now(), now())",
                reservationId.value(),
                UUID.randomUUID());
    }

    @Test
    @DisplayName("should save and read back an outcome message with the same JSON payload")
    void shouldRoundTripOutcomeMessage() {
        OutboundMessage message = new OutboundMessage(
                UUID.randomUUID(),
                "inventory.stock-reserved",
                reservationId.toString(),
                "{\"eventId\":\"e\",\"version\":1,\"items\":[{\"productId\":\"SKU-001\",\"quantity\":3}]}");

        transactions.executeWithoutResult(status -> outcomeMessages.save(reservationId, message));
        Optional<OutboundMessage> found = outcomeMessages.findByEventId(message.eventId());

        assertThat(found).hasValueSatisfying(stored -> {
            assertThat(stored.eventId()).isEqualTo(message.eventId());
            assertThat(stored.topic()).isEqualTo(message.topic());
            assertThat(stored.key()).isEqualTo(message.key());
            assertThat(TestKafkaClient.json(stored.payload())).isEqualTo(TestKafkaClient.json(message.payload()));
        });
    }

    @Test
    @DisplayName("should save and find a processed event linked to its reservation and outcome message")
    void shouldRoundTripProcessedEvent() {
        Instant processedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OutboundMessage message = new OutboundMessage(UUID.randomUUID(), "inventory.stock-reserved", "k", "{}");
        ProcessedEvent event = new ProcessedEvent(
                UUID.randomUUID(),
                "orders.order-placed",
                ProcessedOutcome.RESERVATION_DECIDED,
                Optional.of(reservationId),
                Optional.of(message.eventId()),
                processedAt);

        transactions.executeWithoutResult(status -> {
            outcomeMessages.save(reservationId, message);
            processedEvents.save(event);
        });

        assertThat(processedEvents.find(event.eventId())).contains(event);
    }

    @Test
    @DisplayName("should save and find a processed event without reservation or outcome message")
    void shouldRoundTripProcessedEventWithoutReservation() {
        ProcessedEvent event = new ProcessedEvent(
                UUID.randomUUID(),
                "orders.order-cancelled",
                ProcessedOutcome.CANCELLATION_RECORDED,
                Optional.empty(),
                Optional.empty(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        transactions.executeWithoutResult(status -> processedEvents.save(event));

        assertThat(processedEvents.find(event.eventId())).contains(event);
    }

    @Test
    @DisplayName("should return empty for unknown ids")
    void shouldReturnEmptyForUnknownIds() {
        assertThat(processedEvents.find(UUID.randomUUID())).isEmpty();
        assertThat(outcomeMessages.findByEventId(UUID.randomUUID())).isEmpty();
    }
}
