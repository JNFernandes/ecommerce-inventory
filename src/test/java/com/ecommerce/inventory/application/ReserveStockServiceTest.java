package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessageFactory;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.application.port.OutcomeMessageRepository;
import com.ecommerce.inventory.application.port.ProcessedEvent;
import com.ecommerce.inventory.application.port.ProcessedEventRepository;
import com.ecommerce.inventory.application.port.ProcessedOutcome;
import com.ecommerce.inventory.application.port.PublishFailedException;
import com.ecommerce.inventory.domain.event.StockRejected;
import com.ecommerce.inventory.domain.event.StockReserved;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.RequestedItems;
import com.ecommerce.inventory.domain.model.Reservation;
import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.domain.model.ReservationStatus;
import com.ecommerce.inventory.domain.model.StockItem;
import com.ecommerce.inventory.domain.repository.ReservationRepository;
import com.ecommerce.inventory.domain.repository.StockItemRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class ReserveStockServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:15:30.456Z");
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String TOPIC = "orders.order-placed";
    private static final OutboundMessage MESSAGE =
            new OutboundMessage(UUID.randomUUID(), "inventory.stock-reserved", "key", "{}");
    private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);

    @Mock
    private StockItemRepository stockItems;

    @Mock
    private ReservationRepository reservations;

    @Mock
    private OutcomeMessageRepository outcomeMessages;

    @Mock
    private ProcessedEventRepository processedEvents;

    @Mock
    private OutboundMessageFactory messageFactory;

    @Mock
    private OutboundMessagePublisher publisher;

    @Mock
    private OutboundDeadLetterRepository outboundDeadLetters;

    @Captor
    private ArgumentCaptor<Set<ProductId>> lockedIds;

    private final List<String> journal = new ArrayList<>();

    private ReserveStockService service;

    @BeforeEach
    void setUp() {
        service = new ReserveStockService(
                stockItems,
                reservations,
                outcomeMessages,
                processedEvents,
                messageFactory,
                publisher,
                outboundDeadLetters,
                new DeadLetterPolicy(FIRST_RETRY_DELAY),
                new JournalingTransactions(journal),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should store the outcome as an outbound dead letter and still succeed when publishing fails")
    void shouldDeadLetterFailedPublish() {
        givenStock("SKU-001", 10);
        when(messageFactory.create(any(StockReserved.class))).thenReturn(MESSAGE);
        doThrow(new PublishFailedException("Could not publish", new IllegalStateException("broker down")))
                .when(publisher)
                .publish(MESSAGE);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        assertThat(outcome).isInstanceOf(ReserveStockOutcome.Decided.class);
        verify(outboundDeadLetters)
                .recordFailure(
                        eq(MESSAGE),
                        argThat(error -> error.contains("Could not publish") && error.contains("broker down")),
                        eq(NOW.plus(FIRST_RETRY_DELAY)));
    }

    @Test
    @DisplayName("should log and not throw when storing the outbound dead letter also fails")
    void shouldNotThrowWhenDeadLetterStoreFails() {
        givenStock("SKU-001", 10);
        when(messageFactory.create(any(StockReserved.class))).thenReturn(MESSAGE);
        doThrow(new PublishFailedException("Could not publish", new IllegalStateException("broker down")))
                .when(publisher)
                .publish(MESSAGE);
        doThrow(new IllegalStateException("database down"))
                .when(outboundDeadLetters)
                .recordFailure(any(), any(), any());

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        assertThat(outcome).isInstanceOf(ReserveStockOutcome.Decided.class);
    }

    @Test
    @DisplayName("should dead-letter a failing republish of a redelivered event")
    void shouldDeadLetterFailedRepublish() {
        ReservationId existing = ReservationId.newId();
        when(processedEvents.find(EVENT_ID))
                .thenReturn(Optional.of(
                        new ProcessedEvent(EVENT_ID, TOPIC, ProcessedOutcome.RESERVATION_DECIDED, existing, NOW)));
        when(outcomeMessages.findByReservationId(existing)).thenReturn(Optional.of(MESSAGE));
        doThrow(new PublishFailedException("Could not publish", new IllegalStateException("broker down")))
                .when(publisher)
                .publish(MESSAGE);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        assertThat(outcome).isEqualTo(new ReserveStockOutcome.Redelivered(existing));
        verify(outboundDeadLetters).recordFailure(eq(MESSAGE), any(), eq(NOW.plus(FIRST_RETRY_DELAY)));
    }

    @Test
    @DisplayName("should not dead-letter anything when publishing succeeds")
    void shouldNotDeadLetterOnSuccess() {
        givenStock("SKU-001", 10);
        when(messageFactory.create(any(StockReserved.class))).thenReturn(MESSAGE);

        service.reserve(command(line("SKU-001", 3)));

        verify(outboundDeadLetters, never()).recordFailure(any(), any(), any());
    }

    @Test
    @DisplayName("should save stock, reservation, outcome and processed event in one transaction, then publish")
    void shouldSaveInTransactionThenPublish() {
        givenStock("SKU-001", 10, "SKU-002", 25);
        when(messageFactory.create(any(StockReserved.class))).thenReturn(MESSAGE);
        record("saveStock").when(stockItems).saveAll(anyCollection());
        record("saveReservation").when(reservations).save(any(Reservation.class));
        record("saveOutcome").when(outcomeMessages).save(any(), eq(MESSAGE));
        record("saveProcessed").when(processedEvents).save(any(ProcessedEvent.class));
        record("publish").when(publisher).publish(MESSAGE);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3), line("SKU-002", 5)));

        assertThat(journal)
                .containsExactly(
                        "begin", "saveStock", "saveReservation", "saveOutcome", "saveProcessed", "commit", "publish");
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        ReserveStockOutcome.Decided.class,
                        decided -> assertThat(decided.status()).isEqualTo(ReservationStatus.RESERVED));
    }

    @Test
    @DisplayName("should record the inbound event as processed with the new reservation id")
    void shouldRecordProcessedEvent() {
        givenStock("SKU-001", 10);
        when(messageFactory.create(any(StockReserved.class))).thenReturn(MESSAGE);
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        verify(processedEvents).save(processed.capture());
        ReserveStockOutcome.Decided decided = (ReserveStockOutcome.Decided) outcome;
        assertThat(processed.getValue())
                .isEqualTo(new ProcessedEvent(
                        EVENT_ID, TOPIC, ProcessedOutcome.RESERVATION_DECIDED, decided.reservationId(), NOW));
        verify(outcomeMessages).save(decided.reservationId(), MESSAGE);
    }

    @Test
    @DisplayName("should not publish when the transaction fails")
    void shouldNotPublishWhenTransactionFails() {
        givenStock("SKU-001", 10);
        doAnswer(invocation -> {
                    throw new IllegalStateException("database down");
                })
                .when(reservations)
                .save(any(Reservation.class));

        assertThatThrownBy(() -> service.reserve(command(line("SKU-001", 3))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");
        verify(publisher, never()).publish(any());
        assertThat(journal).containsExactly("begin", "rollback");
    }

    @Test
    @DisplayName(
            "should not save stock for a rejected order but still save reservation, outcome and event, then publish")
    void shouldHandleRejectedOrder() {
        givenStock("SKU-004", 5);
        OutboundMessage rejectedMessage =
                new OutboundMessage(UUID.randomUUID(), "inventory.stock-rejected", "key", "{}");
        when(messageFactory.create(any(StockRejected.class))).thenReturn(rejectedMessage);
        record("saveReservation").when(reservations).save(any(Reservation.class));
        record("saveOutcome").when(outcomeMessages).save(any(), eq(rejectedMessage));
        record("saveProcessed").when(processedEvents).save(any(ProcessedEvent.class));
        record("publish").when(publisher).publish(rejectedMessage);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-004", 6), line("SKU-999", 1)));

        verify(stockItems, never()).saveAll(anyCollection());
        assertThat(journal)
                .containsExactly("begin", "saveReservation", "saveOutcome", "saveProcessed", "commit", "publish");
        assertThat(outcome)
                .isInstanceOfSatisfying(
                        ReserveStockOutcome.Decided.class,
                        decided -> assertThat(decided.status()).isEqualTo(ReservationStatus.REJECTED));
    }

    @Test
    @DisplayName("should lock the consolidated product ids in ascending order")
    void shouldLockConsolidatedSortedProducts() {
        givenStock("SKU-001", 10, "SKU-002", 25);
        when(messageFactory.create(any(StockReserved.class))).thenReturn(MESSAGE);

        service.reserve(command(line("SKU-002", 1), line("SKU-001", 2), line("SKU-002", 3)));

        verify(stockItems).lockAllOrderedByProductId(lockedIds.capture());
        assertThat(lockedIds.getValue()).containsExactly(new ProductId("SKU-001"), new ProductId("SKU-002"));
    }

    @Test
    @DisplayName("should republish the stored outcome and change nothing when the event was already processed")
    void shouldRepublishStoredOutcomeOnRedelivery() {
        ReservationId existing = ReservationId.newId();
        when(processedEvents.find(EVENT_ID))
                .thenReturn(Optional.of(
                        new ProcessedEvent(EVENT_ID, TOPIC, ProcessedOutcome.RESERVATION_DECIDED, existing, NOW)));
        when(outcomeMessages.findByReservationId(existing)).thenReturn(Optional.of(MESSAGE));
        record("publish").when(publisher).publish(MESSAGE);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        assertThat(outcome).isEqualTo(new ReserveStockOutcome.Redelivered(existing));
        assertThat(journal).containsExactly("begin", "commit", "publish");
        verify(stockItems, never()).lockAllOrderedByProductId(anySet());
        verify(reservations, never()).save(any());
        verify(processedEvents, never()).save(any());
        verify(outcomeMessages, never()).save(any(), any());
    }

    @Test
    @DisplayName("should do nothing for a redelivered event that was ignored as a duplicate order")
    void shouldIgnoreRedeliveredDuplicate() {
        ReservationId existing = ReservationId.newId();
        when(processedEvents.find(EVENT_ID))
                .thenReturn(Optional.of(
                        new ProcessedEvent(EVENT_ID, TOPIC, ProcessedOutcome.DUPLICATE_ORDER_IGNORED, existing, NOW)));

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        assertThat(outcome).isEqualTo(new ReserveStockOutcome.DuplicateOrderIgnored(existing));
        verify(publisher, never()).publish(any());
        verify(stockItems, never()).lockAllOrderedByProductId(anySet());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("should ignore a new event for an order that already has a reservation and publish nothing")
    void shouldIgnoreDuplicateOrder() {
        Reservation existing = Reservation.restore(
                ReservationId.newId(),
                ORDER_ID,
                ReservationStatus.RESERVED,
                RequestedItems.consolidate(List.of(line("SKU-001", 1))),
                NOW,
                NOW);
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(existing));
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);

        ReserveStockOutcome outcome = service.reserve(command(line("SKU-001", 3)));

        assertThat(outcome).isEqualTo(new ReserveStockOutcome.DuplicateOrderIgnored(existing.id()));
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue())
                .isEqualTo(new ProcessedEvent(
                        EVENT_ID, TOPIC, ProcessedOutcome.DUPLICATE_ORDER_IGNORED, existing.id(), NOW));
        verify(stockItems, never()).lockAllOrderedByProductId(anySet());
        verify(reservations, never()).save(any());
        verify(publisher, never()).publish(any());
    }

    @Test
    @DisplayName("should fail loudly when a processed event has no stored outcome message")
    void shouldFailWhenStoredOutcomeIsMissing() {
        ReservationId existing = ReservationId.newId();
        when(processedEvents.find(EVENT_ID))
                .thenReturn(Optional.of(
                        new ProcessedEvent(EVENT_ID, TOPIC, ProcessedOutcome.RESERVATION_DECIDED, existing, NOW)));

        assertThatThrownBy(() -> service.reserve(command(line("SKU-001", 3))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(existing.toString());
        verify(publisher, never()).publish(any());
    }

    private org.mockito.stubbing.Stubber record(String step) {
        return doAnswer(invocation -> {
            journal.add(step);
            return null;
        });
    }

    private void givenStock(Object... productAndQuantity) {
        Map<ProductId, StockItem> stock = new LinkedHashMap<>();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            ProductId id = new ProductId((String) productAndQuantity[i]);
            stock.put(id, new StockItem(id, (Integer) productAndQuantity[i + 1]));
        }
        when(stockItems.lockAllOrderedByProductId(anySet())).thenReturn(stock);
    }

    private static ReserveStockCommand command(RequestedItem... lines) {
        return new ReserveStockCommand(EVENT_ID, TOPIC, ORDER_ID, List.of(lines));
    }

    private static RequestedItem line(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }

    /** Runs the callback directly and journals begin, commit or rollback. */
    private static final class JournalingTransactions implements TransactionOperations {

        private final List<String> journal;

        JournalingTransactions(List<String> journal) {
            this.journal = journal;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            journal.add("begin");
            TransactionStatus status = new SimpleTransactionStatus();
            try {
                T result = action.doInTransaction(status);
                journal.add("commit");
                return result;
            } catch (RuntimeException e) {
                journal.add("rollback");
                throw e;
            }
        }
    }
}
