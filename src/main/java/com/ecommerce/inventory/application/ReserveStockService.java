package com.ecommerce.inventory.application;

import com.ecommerce.inventory.application.port.OrderLock;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessageFactory;
import com.ecommerce.inventory.application.port.OutcomeMessageRepository;
import com.ecommerce.inventory.application.port.ProcessedEvent;
import com.ecommerce.inventory.application.port.ProcessedEventRepository;
import com.ecommerce.inventory.application.port.ProcessedOutcome;
import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.model.ProductId;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Reserves stock for a placed order (US-01). Orchestration only; every rule lives in the domain.
 *
 * <p>Write flow (constitution Principle III, ADR 0004): one transaction saves the stock changes, the
 * reservation, the outcome message and the processed-event record; only after it commits is the outcome
 * published through {@link OutcomePublisher}. The caller acknowledges the offset after this method
 * returns.
 *
 * <p>Concurrency: the transaction first takes the per-order lock (ADR 0005), so a placement and a
 * cancellation of the same order never interleave; then stock rows are locked in ascending product id
 * order (ADR 0002), so concurrent orders never oversell and never deadlock. Two placements of the same
 * order are serialised by the order lock, and the unique {@code reservations.order_id} constraint
 * remains as a safety net. Proven by {@code ConcurrentReservationIT} and {@code PlaceCancelRaceIT}.
 */
@Service
public class ReserveStockService {

    private static final Logger LOG = LoggerFactory.getLogger(ReserveStockService.class);

    private final StockItemRepository stockItems;
    private final ReservationRepository reservations;
    private final CancellationMarkerRepository cancellationMarkers;
    private final OutcomeMessageRepository outcomeMessages;
    private final ProcessedEventRepository processedEvents;
    private final OutboundMessageFactory messageFactory;
    private final OutcomePublisher outcomePublisher;
    private final OrderLock orderLock;
    private final TransactionOperations transactions;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param stockItems stock persistence
     * @param reservations reservation persistence
     * @param cancellationMarkers orders cancelled before their placement
     * @param outcomeMessages stored outcome messages
     * @param processedEvents processed inbound events
     * @param messageFactory maps domain events to outbound messages
     * @param outcomePublisher publishes outcomes, dead-lettering failures
     * @param orderLock serialises all work on one order
     * @param transactions transaction boundary
     * @param clock source of decision times
     */
    public ReserveStockService(
            StockItemRepository stockItems,
            ReservationRepository reservations,
            CancellationMarkerRepository cancellationMarkers,
            OutcomeMessageRepository outcomeMessages,
            ProcessedEventRepository processedEvents,
            OutboundMessageFactory messageFactory,
            OutcomePublisher outcomePublisher,
            OrderLock orderLock,
            TransactionOperations transactions,
            Clock clock) {
        this.stockItems = stockItems;
        this.reservations = reservations;
        this.cancellationMarkers = cancellationMarkers;
        this.outcomeMessages = outcomeMessages;
        this.processedEvents = processedEvents;
        this.messageFactory = messageFactory;
        this.outcomePublisher = outcomePublisher;
        this.orderLock = orderLock;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Decides and saves the reservation of an order in one transaction, then publishes its outcome.
     *
     * @param command the placed order
     * @return what was done
     */
    public ReserveStockOutcome reserve(ReserveStockCommand command) {
        Step step = Objects.requireNonNull(transactions.execute(status -> handleInTransaction(command)));
        return switch (step) {
            case Decision decision -> {
                outcomePublisher.publish(decision.message(), command.orderId());
                LOG.info(
                        "Order {} handled: reservation {} {}, inbound event {}, published event {} to {}",
                        command.orderId(),
                        decision.reservationId(),
                        decision.status(),
                        command.eventId(),
                        decision.message().eventId(),
                        decision.message().topic());
                yield new ReserveStockOutcome.Decided(decision.reservationId(), decision.status());
            }
            case Redelivery redelivery -> {
                outcomePublisher.publish(redelivery.message(), command.orderId());
                LOG.info(
                        "Redelivered event {} for order {} already processed: republished event {} of reservation {}",
                        command.eventId(),
                        command.orderId(),
                        redelivery.message().eventId(),
                        redelivery.reservationId());
                yield new ReserveStockOutcome.Redelivered(redelivery.reservationId());
            }
            case Ignored ignored -> ignored.outcome();
        };
    }

    /**
     * Order lock first, then idempotency (US-01 FR-014 to FR-016), the cancellation marker (US-02
     * FR-011) and the duplicate-order check, then the decision.
     */
    private Step handleInTransaction(ReserveStockCommand command) {
        orderLock.lock(command.orderId());
        Optional<ProcessedEvent> processed = processedEvents.find(command.eventId());
        if (processed.isPresent()) {
            return redelivered(command, processed.get());
        }
        if (cancellationMarkers.exists(command.orderId())) {
            saveProcessed(command, ProcessedOutcome.PLACEMENT_CANCELLED, Optional.empty(), Optional.empty());
            LOG.info(
                    "OrderPlaced for order {} ignored: order already cancelled (event {})",
                    command.orderId(),
                    command.eventId());
            return new Ignored(new ReserveStockOutcome.PlacementCancelled());
        }
        Optional<Reservation> existing = reservations.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            ReservationId existingId = existing.get().id();
            saveProcessed(command, ProcessedOutcome.DUPLICATE_ORDER_IGNORED, Optional.of(existingId), Optional.empty());
            LOG.info(
                    "Duplicate OrderPlaced ignored: order {}, new event {}, existing reservation {}",
                    command.orderId(),
                    command.eventId(),
                    existingId);
            return new Ignored(new ReserveStockOutcome.DuplicateOrderIgnored(existingId));
        }
        return decideAndSave(command);
    }

    /**
     * A known event changes nothing. Only an event that decided a reservation republishes its stored
     * outcome; every other outcome publishes nothing (US-02 FR-021).
     */
    private Step redelivered(ReserveStockCommand command, ProcessedEvent processed) {
        switch (processed.outcome()) {
            case RESERVATION_DECIDED -> {
                UUID outcomeEventId = processed.outcomeEventId().orElseThrow();
                OutboundMessage stored = outcomeMessages
                        .findByEventId(outcomeEventId)
                        .orElseThrow(() -> new IllegalStateException("Event " + command.eventId()
                                + " is processed but its outcome message " + outcomeEventId + " is missing"));
                return new Redelivery(processed.reservationId().orElseThrow(), stored);
            }
            case PLACEMENT_CANCELLED -> {
                LOG.info("Redelivered event {}: order {} was already cancelled", command.eventId(), command.orderId());
                return new Ignored(new ReserveStockOutcome.PlacementCancelled());
            }
            default -> {
                LOG.info("Redelivered event {} was ignored as a duplicate order before", command.eventId());
                return new Ignored(new ReserveStockOutcome.DuplicateOrderIgnored(
                        processed.reservationId().orElseThrow()));
            }
        }
    }

    private Decision decideAndSave(ReserveStockCommand command) {
        Instant now = clock.instant();
        RequestedItems items = RequestedItems.consolidate(command.items());
        Map<ProductId, StockItem> lockedStock = stockItems.lockAllOrderedByProductId(items.productIds());
        Reservation reservation = Reservation.decide(ReservationId.newId(), command.orderId(), items, lockedStock, now);
        if (reservation.status() == ReservationStatus.RESERVED) {
            stockItems.saveAll(lockedStock.values());
        }
        reservations.save(reservation);
        List<InventoryDomainEvent> events = reservation.pullDomainEvents();
        if (events.size() != 1) {
            throw new IllegalStateException("Expected one domain event per decision, got " + events.size());
        }
        OutboundMessage message = messageFactory.create(events.getFirst());
        outcomeMessages.save(reservation.id(), message);
        saveProcessed(
                command,
                ProcessedOutcome.RESERVATION_DECIDED,
                Optional.of(reservation.id()),
                Optional.of(message.eventId()));
        return new Decision(reservation.id(), reservation.status(), message);
    }

    private void saveProcessed(
            ReserveStockCommand command,
            ProcessedOutcome outcome,
            Optional<ReservationId> reservationId,
            Optional<UUID> outcomeEventId) {
        processedEvents.save(new ProcessedEvent(
                command.eventId(), command.topic(), outcome, reservationId, outcomeEventId, clock.instant()));
    }

    /** What the transaction decided; publishing happens only after it has committed. */
    private sealed interface Step permits Decision, Redelivery, Ignored {}

    private record Decision(ReservationId reservationId, ReservationStatus status, OutboundMessage message)
            implements Step {}

    private record Redelivery(ReservationId reservationId, OutboundMessage message) implements Step {}

    private record Ignored(ReserveStockOutcome outcome) implements Step {}
}
