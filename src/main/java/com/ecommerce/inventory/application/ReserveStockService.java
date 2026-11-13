package com.ecommerce.inventory.application;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessageFactory;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
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
import com.ecommerce.inventory.domain.repository.ReservationRepository;
import com.ecommerce.inventory.domain.repository.StockItemRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Reserves stock for a placed order (US-01). Orchestration only; every rule lives in the domain.
 *
 * <p>Write flow (constitution Principle III, research R3): one transaction saves the stock changes,
 * the reservation, the outcome message and the processed-event record; only after it commits is the
 * outcome published. The caller acknowledges the offset after this method returns.
 *
 * <p>Concurrency (ADR 0002): stock rows are locked in ascending product id order, so concurrent orders
 * never oversell and never deadlock. Two events for the same order processed at the same time (from
 * different partitions) cannot both create a reservation: the loser's insert violates the unique
 * {@code reservations.order_id} constraint at commit, its whole transaction (including any stock
 * change) rolls back, and the exception goes to the container error handler. On retry the loser sees
 * the winner's reservation and is ignored as a duplicate order. Proven by
 * {@code ConcurrentReservationIT}.
 */
@Service
public class ReserveStockService {

    private static final Logger LOG = LoggerFactory.getLogger(ReserveStockService.class);

    private final StockItemRepository stockItems;
    private final ReservationRepository reservations;
    private final OutcomeMessageRepository outcomeMessages;
    private final ProcessedEventRepository processedEvents;
    private final OutboundMessageFactory messageFactory;
    private final OutboundMessagePublisher publisher;
    private final TransactionOperations transactions;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param stockItems stock persistence
     * @param reservations reservation persistence
     * @param outcomeMessages stored outcome messages
     * @param processedEvents processed inbound events
     * @param messageFactory maps domain events to outbound messages
     * @param publisher publishes outbound messages
     * @param transactions transaction boundary
     * @param clock source of decision times
     */
    public ReserveStockService(
            StockItemRepository stockItems,
            ReservationRepository reservations,
            OutcomeMessageRepository outcomeMessages,
            ProcessedEventRepository processedEvents,
            OutboundMessageFactory messageFactory,
            OutboundMessagePublisher publisher,
            TransactionOperations transactions,
            Clock clock) {
        this.stockItems = stockItems;
        this.reservations = reservations;
        this.outcomeMessages = outcomeMessages;
        this.processedEvents = processedEvents;
        this.messageFactory = messageFactory;
        this.publisher = publisher;
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
                publisher.publish(decision.message());
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
                publisher.publish(redelivery.message());
                LOG.info(
                        "Redelivered event {} for order {} already processed: republished event {} of reservation {}",
                        command.eventId(),
                        command.orderId(),
                        redelivery.message().eventId(),
                        redelivery.reservationId());
                yield new ReserveStockOutcome.Redelivered(redelivery.reservationId());
            }
            case Ignored ignored -> new ReserveStockOutcome.DuplicateOrderIgnored(ignored.existingReservationId());
        };
    }

    /**
     * Idempotency (FR-014 to FR-016): a known event id changes nothing and at most republishes the
     * stored outcome; a new event for an order that already has a reservation is recorded and ignored.
     */
    private Step handleInTransaction(ReserveStockCommand command) {
        Optional<ProcessedEvent> processed = processedEvents.find(command.eventId());
        if (processed.isPresent()) {
            ReservationId reservationId = processed.get().reservationId();
            if (processed.get().outcome() == ProcessedOutcome.DUPLICATE_ORDER_IGNORED) {
                LOG.info("Redelivered event {} was ignored as a duplicate order before", command.eventId());
                return new Ignored(reservationId);
            }
            OutboundMessage stored = outcomeMessages
                    .findByReservationId(reservationId)
                    .orElseThrow(() -> new IllegalStateException("Event " + command.eventId()
                            + " is processed but reservation " + reservationId + " has no stored outcome message"));
            return new Redelivery(reservationId, stored);
        }
        Optional<Reservation> existing = reservations.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            ReservationId existingId = existing.get().id();
            processedEvents.save(new ProcessedEvent(
                    command.eventId(),
                    command.topic(),
                    ProcessedOutcome.DUPLICATE_ORDER_IGNORED,
                    existingId,
                    clock.instant()));
            LOG.info(
                    "Duplicate OrderPlaced ignored: order {}, new event {}, existing reservation {}",
                    command.orderId(),
                    command.eventId(),
                    existingId);
            return new Ignored(existingId);
        }
        return decideAndSave(command);
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
        processedEvents.save(new ProcessedEvent(
                command.eventId(), command.topic(), ProcessedOutcome.RESERVATION_DECIDED, reservation.id(), now));
        return new Decision(reservation.id(), reservation.status(), message);
    }

    /** What the transaction decided; publishing happens only after it has committed. */
    private sealed interface Step permits Decision, Redelivery, Ignored {}

    private record Decision(ReservationId reservationId, ReservationStatus status, OutboundMessage message)
            implements Step {}

    private record Redelivery(ReservationId reservationId, OutboundMessage message) implements Step {}

    private record Ignored(ReservationId existingReservationId) implements Step {}
}
