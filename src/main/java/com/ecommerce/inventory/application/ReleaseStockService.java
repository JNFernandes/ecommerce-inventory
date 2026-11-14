package com.ecommerce.inventory.application;

import com.ecommerce.inventory.application.port.OrderLock;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessageFactory;
import com.ecommerce.inventory.application.port.OutcomeMessageRepository;
import com.ecommerce.inventory.application.port.ProcessedEvent;
import com.ecommerce.inventory.application.port.ProcessedEventRepository;
import com.ecommerce.inventory.application.port.ProcessedOutcome;
import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.model.CancellationMarker;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.ReleaseOutcome;
import com.ecommerce.inventory.domain.model.Reservation;
import com.ecommerce.inventory.domain.model.ReservationId;
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
 * Releases the reserved stock of a cancelled order (US-02). Orchestration only; whether and what to
 * release is decided by {@link Reservation#release}.
 *
 * <p>One transaction takes the per-order lock (ADR 0005), checks idempotency, finds the reservation or
 * records a cancellation marker, locks the reservation's stock rows in product id order (ADR 0002) and
 * saves the stock, the reservation status, the outcome message and the processed event together. The
 * stock-released message is published through {@link OutcomePublisher} only after the commit; the
 * caller acknowledges the offset after this method returns.
 */
@Service
public class ReleaseStockService {

    private static final Logger LOG = LoggerFactory.getLogger(ReleaseStockService.class);

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
     * @param clock source of release times
     */
    public ReleaseStockService(
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
     * Handles the cancellation of an order in one transaction, then publishes stock-released if stock
     * was given back.
     *
     * @param command the cancelled order
     * @return what was done
     */
    public ReleaseStockOutcome release(ReleaseStockCommand command) {
        Step step = Objects.requireNonNull(transactions.execute(status -> handleInTransaction(command)));
        return switch (step) {
            case Publish publish -> {
                outcomePublisher.publish(publish.message(), command.orderId());
                if (publish.redelivery()) {
                    LOG.info(
                            "Redelivered cancellation {} of order {}: republished event {} of reservation {}",
                            command.eventId(),
                            command.orderId(),
                            publish.message().eventId(),
                            publish.reservationId());
                    yield new ReleaseStockOutcome.Redelivered(publish.reservationId());
                }
                LOG.info(
                        "Order {} cancelled: reservation {} RELEASED, inbound event {}, published event {} to {}",
                        command.orderId(),
                        publish.reservationId(),
                        command.eventId(),
                        publish.message().eventId(),
                        publish.message().topic());
                yield new ReleaseStockOutcome.Released(publish.reservationId());
            }
            case Done done -> done.outcome();
        };
    }

    private Step handleInTransaction(ReleaseStockCommand command) {
        orderLock.lock(command.orderId());
        Optional<ProcessedEvent> processed = processedEvents.find(command.eventId());
        if (processed.isPresent()) {
            return redelivered(command, processed.get());
        }
        Optional<Reservation> reservation = reservations.findByOrderId(command.orderId());
        if (reservation.isEmpty()) {
            return cancellationBeforePlacement(command);
        }
        return release(command, reservation.get());
    }

    /** Only a cancellation that released stock republishes its stored message (FR-015). */
    private Step redelivered(ReleaseStockCommand command, ProcessedEvent processed) {
        if (processed.outcome() == ProcessedOutcome.STOCK_RELEASED) {
            UUID outcomeEventId = processed.outcomeEventId().orElseThrow();
            OutboundMessage stored = outcomeMessages
                    .findByEventId(outcomeEventId)
                    .orElseThrow(() -> new IllegalStateException("Event " + command.eventId()
                            + " is processed but its outcome message " + outcomeEventId + " is missing"));
            return new Publish(processed.reservationId().orElseThrow(), stored, true);
        }
        LOG.info(
                "Redelivered cancellation {} of order {} was {} before; nothing to do",
                command.eventId(),
                command.orderId(),
                processed.outcome());
        return new Done(new ReleaseStockOutcome.Ignored(processed.reservationId(), "already processed"));
    }

    /** Race rule (FR-010, FR-013): remember the cancellation so a later placement reserves nothing. */
    private Step cancellationBeforePlacement(ReleaseStockCommand command) {
        if (cancellationMarkers.exists(command.orderId())) {
            saveProcessed(command, ProcessedOutcome.CANCELLATION_IGNORED, Optional.empty(), Optional.empty());
            LOG.info(
                    "Cancellation of order {} ignored: already recorded as cancelled (event {})",
                    command.orderId(),
                    command.eventId());
            return new Done(new ReleaseStockOutcome.Ignored(Optional.empty(), "already recorded as cancelled"));
        }
        cancellationMarkers.save(new CancellationMarker(command.orderId(), command.eventId(), clock.instant()));
        saveProcessed(command, ProcessedOutcome.CANCELLATION_RECORDED, Optional.empty(), Optional.empty());
        LOG.info(
                "Cancellation of order {} arrived before its placement: recorded (event {})",
                command.orderId(),
                command.eventId());
        return new Done(new ReleaseStockOutcome.CancellationRecorded());
    }

    private Step release(ReleaseStockCommand command, Reservation reservation) {
        Instant now = clock.instant();
        Map<ProductId, StockItem> lockedStock =
                stockItems.lockAllOrderedByProductId(reservation.items().productIds());
        ReleaseOutcome outcome = reservation.release(lockedStock, now);
        if (outcome != ReleaseOutcome.RELEASED) {
            String reason = outcome == ReleaseOutcome.NOTHING_RESERVED ? "nothing was reserved" : "already released";
            saveProcessed(
                    command, ProcessedOutcome.CANCELLATION_IGNORED, Optional.of(reservation.id()), Optional.empty());
            LOG.info(
                    "Cancellation of order {} ignored: {} (event {}, reservation {})",
                    command.orderId(),
                    reason,
                    command.eventId(),
                    reservation.id());
            return new Done(new ReleaseStockOutcome.Ignored(Optional.of(reservation.id()), reason));
        }
        stockItems.saveAll(lockedStock.values());
        reservations.update(reservation);
        List<InventoryDomainEvent> events = reservation.pullDomainEvents();
        if (events.size() != 1) {
            throw new IllegalStateException("Expected one domain event per release, got " + events.size());
        }
        OutboundMessage message = messageFactory.create(events.getFirst());
        outcomeMessages.save(reservation.id(), message);
        saveProcessed(
                command,
                ProcessedOutcome.STOCK_RELEASED,
                Optional.of(reservation.id()),
                Optional.of(message.eventId()));
        return new Publish(reservation.id(), message, false);
    }

    private void saveProcessed(
            ReleaseStockCommand command,
            ProcessedOutcome outcome,
            Optional<ReservationId> reservationId,
            Optional<UUID> outcomeEventId) {
        processedEvents.save(new ProcessedEvent(
                command.eventId(), command.topic(), outcome, reservationId, outcomeEventId, clock.instant()));
    }

    /** What the transaction decided; publishing happens only after it has committed. */
    private sealed interface Step permits Publish, Done {}

    private record Publish(ReservationId reservationId, OutboundMessage message, boolean redelivery) implements Step {}

    private record Done(ReleaseStockOutcome outcome) implements Step {}
}
