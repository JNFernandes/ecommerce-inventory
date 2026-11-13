package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.event.StockReserved;
import com.ecommerce.inventory.domain.exception.InsufficientStockException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationReserveTest {

    private static final ReservationId RESERVATION_ID = ReservationId.newId();
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-13T10:15:30.456Z");

    @Test
    @DisplayName("should reserve every item and decrease each stock item")
    void shouldReserveEveryItem() {
        Map<ProductId, StockItem> stock = stock("SKU-001", 10, "SKU-002", 25);

        Reservation reservation =
                Reservation.decide(RESERVATION_ID, ORDER_ID, items(line("SKU-001", 3), line("SKU-002", 5)), stock, NOW);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.id()).isEqualTo(RESERVATION_ID);
        assertThat(reservation.orderId()).isEqualTo(ORDER_ID);
        assertThat(reservation.createdAt()).isEqualTo(NOW);
        assertThat(reservation.updatedAt()).isEqualTo(NOW);
        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(7);
        assertThat(stock.get(new ProductId("SKU-002")).availableQuantity()).isEqualTo(20);
    }

    @Test
    @DisplayName("should allow reserving the exact available quantity, leaving zero")
    void shouldReserveExactStock() {
        Map<ProductId, StockItem> stock = stock("SKU-004", 5);

        Reservation.decide(RESERVATION_ID, ORDER_ID, items(line("SKU-004", 5)), stock, NOW);

        assertThat(stock.get(new ProductId("SKU-004")).availableQuantity()).isZero();
    }

    @Test
    @DisplayName("should reserve the consolidated quantity for duplicate lines")
    void shouldReserveConsolidatedQuantity() {
        Map<ProductId, StockItem> stock = stock("SKU-001", 10);

        Reservation reservation =
                Reservation.decide(RESERVATION_ID, ORDER_ID, items(line("SKU-001", 2), line("SKU-001", 3)), stock, NOW);

        assertThat(reservation.items().items()).containsExactly(line("SKU-001", 5));
        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("should raise exactly one StockReserved event with the reservation details")
    void shouldRaiseStockReserved() {
        Reservation reservation =
                Reservation.decide(RESERVATION_ID, ORDER_ID, items(line("SKU-001", 3)), stock("SKU-001", 10), NOW);

        List<InventoryDomainEvent> events = reservation.pullDomainEvents();

        assertThat(events)
                .containsExactly(new StockReserved(RESERVATION_ID, ORDER_ID, List.of(line("SKU-001", 3)), NOW));
        assertThat(events.getFirst().occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("should return the domain events only once")
    void shouldPullEventsOnce() {
        Reservation reservation =
                Reservation.decide(RESERVATION_ID, ORDER_ID, items(line("SKU-001", 3)), stock("SKU-001", 10), NOW);

        reservation.pullDomainEvents();

        assertThat(reservation.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("should leave all stock untouched when one item cannot be supplied")
    void shouldBeAllOrNothing() {
        Map<ProductId, StockItem> stock = stock("SKU-001", 10, "SKU-004", 5);

        assertThatThrownBy(() -> Reservation.decide(
                        RESERVATION_ID, ORDER_ID, items(line("SKU-001", 2), line("SKU-004", 6)), stock, NOW))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(10);
        assertThat(stock.get(new ProductId("SKU-004")).availableQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("should restore a persisted reservation without raising events")
    void shouldRestoreWithoutEvents() {
        Reservation restored = Reservation.restore(
                RESERVATION_ID, ORDER_ID, ReservationStatus.RESERVED, items(line("SKU-001", 3)), NOW, NOW);

        assertThat(restored.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(restored.pullDomainEvents()).isEmpty();
    }

    private static RequestedItem line(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }

    private static RequestedItems items(RequestedItem... lines) {
        return RequestedItems.consolidate(List.of(lines));
    }

    private static Map<ProductId, StockItem> stock(Object... productAndQuantity) {
        Map<ProductId, StockItem> stock = new LinkedHashMap<>();
        for (int i = 0; i < productAndQuantity.length; i += 2) {
            ProductId id = new ProductId((String) productAndQuantity[i]);
            stock.put(id, new StockItem(id, (Integer) productAndQuantity[i + 1]));
        }
        return stock;
    }
}
