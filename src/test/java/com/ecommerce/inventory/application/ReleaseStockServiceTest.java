package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.port.OrderLock;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessageFactory;
import com.ecommerce.inventory.application.port.OutcomeMessageRepository;
import com.ecommerce.inventory.application.port.ProcessedEvent;
import com.ecommerce.inventory.application.port.ProcessedEventRepository;
import com.ecommerce.inventory.application.port.ProcessedOutcome;
import com.ecommerce.inventory.domain.event.StockReleased;
import com.ecommerce.inventory.domain.exception.StockItemMissingException;
import com.ecommerce.inventory.domain.model.CancellationMarker;
import com.ecommerce.inventory.domain.model.FailureKind;
import com.ecommerce.inventory.domain.model.ItemFailure;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
import com.ecommerce.inventory.domain.model.Rejection;
import com.ecommerce.inventory.domain.model.RejectionReason;
import com.ecommerce.inventory.domain.model.RequestedItem;
import com.ecommerce.inventory.domain.model.RequestedItems;
import com.ecommerce.inventory.domain.model.Reservation;
import com.ecommerce.inventory.domain.model.ReservationId;
import com.ecommerce.inventory.domain.model.ReservationStatus;
import com.ecommerce.inventory.domain.model.StockItem;
import com.ecommerce.inventory.domain.repository.CancellationMarkerRepository;
import com.ecommerce.inventory.domain.repository.ReservationRepository;
import com.ecommerce.inventory.domain.repository.StockItemRepository;
import java.time.Clock;
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
import org.mockito.stubbing.Stubber;

@ExtendWith(MockitoExtension.class)
class ReleaseStockServiceTest {

    private static final Instant RESERVED_AT = Instant.parse("2026-11-13T10:15:30.456Z");
    private static final Instant NOW = Instant.parse("2026-11-13T10:20:00.512Z");
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final String TOPIC = "orders.order-cancelled";
    private static final OutboundMessage MESSAGE =
            new OutboundMessage(UUID.randomUUID(), "inventory.stock-released", "key", "{}");

    @Mock
    private StockItemRepository stockItems;

    @Mock
    private ReservationRepository reservations;

    @Mock
    private CancellationMarkerRepository cancellationMarkers;

    @Mock
    private OutcomeMessageRepository outcomeMessages;

    @Mock
    private ProcessedEventRepository processedEvents;

    @Mock
    private OutboundMessageFactory messageFactory;

    @Mock
    private OutcomePublisher outcomePublisher;

    @Mock
    private OrderLock orderLock;

    @Captor
    private ArgumentCaptor<Set<ProductId>> lockedIds;

    @Captor
    private ArgumentCaptor<ProcessedEvent> processed;

    private final List<String> journal = new ArrayList<>();

    private ReleaseStockService service;

    @BeforeEach
    void setUp() {
        service = new ReleaseStockService(
                stockItems,
                reservations,
                cancellationMarkers,
                outcomeMessages,
                processedEvents,
                messageFactory,
                outcomePublisher,
                orderLock,
                new JournalingTransactions(journal),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should lock the order, release, save everything in one transaction, then publish")
    void shouldReleaseInTransactionThenPublish() {
        Reservation reservation = reserved(line("SKU-001", 3), line("SKU-002", 5));
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        givenStock("SKU-001", 7, "SKU-002", 20);
        when(messageFactory.create(any(StockReleased.class))).thenReturn(MESSAGE);
        record("lockOrder").when(orderLock).lock(ORDER_ID);
        record("saveStock").when(stockItems).saveAll(anyCollection());
        record("updateReservation").when(reservations).update(reservation);
        record("saveOutcome").when(outcomeMessages).save(reservation.id(), MESSAGE);
        record("saveProcessed").when(processedEvents).save(any(ProcessedEvent.class));
        record("publish").when(outcomePublisher).publish(MESSAGE, ORDER_ID);

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome).isEqualTo(new ReleaseStockOutcome.Released(reservation.id()));
        assertThat(journal)
                .containsExactly(
                        "begin",
                        "lockOrder",
                        "saveStock",
                        "updateReservation",
                        "saveOutcome",
                        "saveProcessed",
                        "commit",
                        "publish");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("should lock exactly the reservation's products and record STOCK_RELEASED with the outcome id")
    void shouldLockReservationProductsAndRecordOutcome() {
        Reservation reservation = reserved(line("SKU-002", 5), line("SKU-001", 3));
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        givenStock("SKU-001", 7, "SKU-002", 20);
        when(messageFactory.create(any(StockReleased.class))).thenReturn(MESSAGE);

        service.release(command());

        verify(stockItems).lockAllOrderedByProductId(lockedIds.capture());
        assertThat(lockedIds.getValue()).containsExactly(new ProductId("SKU-001"), new ProductId("SKU-002"));
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue())
                .isEqualTo(new ProcessedEvent(
                        EVENT_ID,
                        TOPIC,
                        ProcessedOutcome.STOCK_RELEASED,
                        Optional.of(reservation.id()),
                        Optional.of(MESSAGE.eventId()),
                        NOW));
    }

    @Test
    @DisplayName("should not publish when the transaction fails")
    void shouldNotPublishWhenTransactionFails() {
        Reservation reservation = reserved(line("SKU-001", 3));
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(reservation));
        givenStock("SKU-001", 7);
        doAnswer(invocation -> {
                    throw new IllegalStateException("database down");
                })
                .when(reservations)
                .update(reservation);

        assertThatThrownBy(() -> service.release(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database down");
        verify(outcomePublisher, never()).publish(any(), any());
        assertThat(journal).containsExactly("begin", "rollback");
    }

    @Test
    @DisplayName("should propagate a missing stock item and publish nothing")
    void shouldPropagateMissingStockItem() {
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(reserved(line("SKU-001", 3))));
        when(stockItems.lockAllOrderedByProductId(anySet())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.release(command())).isInstanceOf(StockItemMissingException.class);
        verify(stockItems, never()).saveAll(anyCollection());
        verify(outcomePublisher, never()).publish(any(), any());
    }

    @Test
    @DisplayName("should ignore the cancellation of a rejected order: no stock change, nothing published")
    void shouldIgnoreRejectedReservation() {
        Reservation rejected = Reservation.restoreRejected(
                ReservationId.newId(),
                ORDER_ID,
                items(line("SKU-005", 1)),
                new Rejection(
                        RejectionReason.INSUFFICIENT_STOCK,
                        List.of(new ItemFailure(new ProductId("SKU-005"), 1, 0, FailureKind.INSUFFICIENT_STOCK))),
                RESERVED_AT,
                RESERVED_AT);
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(rejected));
        givenStock("SKU-005", 0);

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome)
                .isEqualTo(new ReleaseStockOutcome.Ignored(Optional.of(rejected.id()), "nothing was reserved"));
        assertIgnoredWithoutChanges(Optional.of(rejected.id()), ProcessedOutcome.CANCELLATION_IGNORED);
    }

    @Test
    @DisplayName("should ignore the cancellation of an already released order: stock is never given back twice")
    void shouldIgnoreReleasedReservation() {
        Reservation released = Reservation.restoreReleased(
                ReservationId.newId(), ORDER_ID, items(line("SKU-001", 3)), RESERVED_AT, NOW);
        when(reservations.findByOrderId(ORDER_ID)).thenReturn(Optional.of(released));
        givenStock("SKU-001", 10);

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome).isEqualTo(new ReleaseStockOutcome.Ignored(Optional.of(released.id()), "already released"));
        assertIgnoredWithoutChanges(Optional.of(released.id()), ProcessedOutcome.CANCELLATION_IGNORED);
    }

    @Test
    @DisplayName("should record a cancellation marker when the cancellation arrives before the placement")
    void shouldRecordEarlyCancellation() {
        ArgumentCaptor<CancellationMarker> marker = ArgumentCaptor.forClass(CancellationMarker.class);

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome).isEqualTo(new ReleaseStockOutcome.CancellationRecorded());
        verify(cancellationMarkers).save(marker.capture());
        assertThat(marker.getValue()).isEqualTo(new CancellationMarker(ORDER_ID, EVENT_ID, NOW));
        assertIgnoredWithoutChanges(Optional.empty(), ProcessedOutcome.CANCELLATION_RECORDED);
    }

    @Test
    @DisplayName("should ignore a second early cancellation of the same order and keep one marker")
    void shouldIgnoreDuplicateEarlyCancellation() {
        when(cancellationMarkers.exists(ORDER_ID)).thenReturn(true);

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome)
                .isEqualTo(new ReleaseStockOutcome.Ignored(Optional.empty(), "already recorded as cancelled"));
        verify(cancellationMarkers, never()).save(any());
        assertIgnoredWithoutChanges(Optional.empty(), ProcessedOutcome.CANCELLATION_IGNORED);
    }

    @Test
    @DisplayName("should republish the stored stock-released message when a releasing cancellation is redelivered")
    void shouldRepublishOnRedelivery() {
        ReservationId reservationId = ReservationId.newId();
        givenProcessed(ProcessedOutcome.STOCK_RELEASED, Optional.of(reservationId), Optional.of(MESSAGE.eventId()));
        when(outcomeMessages.findByEventId(MESSAGE.eventId())).thenReturn(Optional.of(MESSAGE));
        record("publish").when(outcomePublisher).publish(MESSAGE, ORDER_ID);

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome).isEqualTo(new ReleaseStockOutcome.Redelivered(reservationId));
        assertThat(journal).containsExactly("begin", "commit", "publish");
        verify(reservations, never()).findByOrderId(any());
        verify(stockItems, never()).lockAllOrderedByProductId(anySet());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("should do nothing for a redelivered cancellation that was ignored or recorded before")
    void shouldDoNothingForOtherRedeliveries() {
        givenProcessed(ProcessedOutcome.CANCELLATION_RECORDED, Optional.empty(), Optional.empty());

        ReleaseStockOutcome outcome = service.release(command());

        assertThat(outcome).isEqualTo(new ReleaseStockOutcome.Ignored(Optional.empty(), "already processed"));
        verify(outcomePublisher, never()).publish(any(), any());
        verify(cancellationMarkers, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("should fail loudly when a releasing cancellation has no stored outcome message")
    void shouldFailWhenStoredOutcomeIsMissing() {
        givenProcessed(
                ProcessedOutcome.STOCK_RELEASED, Optional.of(ReservationId.newId()), Optional.of(MESSAGE.eventId()));

        assertThatThrownBy(() -> service.release(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(MESSAGE.eventId().toString());
        verify(outcomePublisher, never()).publish(any(), any());
    }

    private void assertIgnoredWithoutChanges(Optional<ReservationId> reservationId, ProcessedOutcome outcome) {
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue())
                .isEqualTo(new ProcessedEvent(EVENT_ID, TOPIC, outcome, reservationId, Optional.empty(), NOW));
        verify(stockItems, never()).saveAll(anyCollection());
        verify(reservations, never()).update(any());
        verify(outcomeMessages, never()).save(any(), any());
        verify(outcomePublisher, never()).publish(any(), eq(ORDER_ID));
    }

    private void givenProcessed(
            ProcessedOutcome outcome, Optional<ReservationId> reservationId, Optional<UUID> outcomeEventId) {
        when(processedEvents.find(EVENT_ID))
                .thenReturn(
                        Optional.of(new ProcessedEvent(EVENT_ID, TOPIC, outcome, reservationId, outcomeEventId, NOW)));
    }

    private Stubber record(String step) {
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

    private static Reservation reserved(RequestedItem... lines) {
        return Reservation.restore(
                ReservationId.newId(), ORDER_ID, ReservationStatus.RESERVED, items(lines), RESERVED_AT, RESERVED_AT);
    }

    private static RequestedItems items(RequestedItem... lines) {
        return RequestedItems.consolidate(List.of(lines));
    }

    private static RequestedItem line(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }

    private static ReleaseStockCommand command() {
        return new ReleaseStockCommand(EVENT_ID, TOPIC, ORDER_ID);
    }
}
