package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.event.StockReleased;
import com.ecommerce.inventory.domain.exception.StockItemMissingException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationReleaseTest {

    private static final ReservationId RESERVATION_ID = ReservationId.newId();
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final Instant RESERVED_AT = Instant.parse("2026-11-13T10:15:30.456Z");
    private static final Instant NOW = Instant.parse("2026-11-13T10:20:00.512Z");

    @Test
    @DisplayName("should give every reserved quantity back and return RELEASED")
    void shouldReleaseEveryItem() {
        Reservation reservation = reserved(line("SKU-001", 3), line("SKU-002", 5));
        Map<ProductId, StockItem> stock = stock("SKU-001", 7, "SKU-002", 20);

        ReleaseOutcome outcome = reservation.release(stock, NOW);

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(10);
        assertThat(stock.get(new ProductId("SKU-002")).availableQuantity()).isEqualTo(25);
    }

    @Test
    @DisplayName("should become RELEASED with the release time as release and update time")
    void shouldMoveToReleased() {
        Reservation reservation = reserved(line("SKU-001", 3));

        reservation.release(stock("SKU-001", 7), NOW);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.releasedAt()).contains(NOW);
        assertThat(reservation.updatedAt()).isEqualTo(NOW);
        assertThat(reservation.createdAt()).isEqualTo(RESERVED_AT);
    }

    @Test
    @DisplayName("should give back the consolidated quantity of duplicate lines")
    void shouldReleaseConsolidatedQuantity() {
        Reservation reservation = reserved(line("SKU-001", 2), line("SKU-001", 3));
        Map<ProductId, StockItem> stock = stock("SKU-001", 5);

        reservation.release(stock, NOW);

        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(10);
    }

    @Test
    @DisplayName("should raise exactly one StockReleased with reservation, order, items and release time")
    void shouldRaiseStockReleased() {
        Reservation reservation = reserved(line("SKU-001", 3));

        reservation.release(stock("SKU-001", 7), NOW);

        assertThat(reservation.pullDomainEvents())
                .containsExactly(new StockReleased(RESERVATION_ID, ORDER_ID, List.of(line("SKU-001", 3)), NOW));
    }

    @Test
    @DisplayName("should throw and change nothing when a reserved product has no stock item")
    void shouldRefuseMissingStockItem() {
        Reservation reservation = reserved(line("SKU-001", 3), line("SKU-002", 5));
        Map<ProductId, StockItem> stock = stock("SKU-001", 7);

        assertThatThrownBy(() -> reservation.release(stock, NOW)).isInstanceOf(StockItemMissingException.class);

        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(7);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("should give nothing back for a REJECTED reservation")
    void shouldNotReleaseRejected() {
        Reservation rejected = Reservation.restoreRejected(
                RESERVATION_ID,
                ORDER_ID,
                items(line("SKU-005", 1)),
                new Rejection(
                        RejectionReason.INSUFFICIENT_STOCK,
                        List.of(new ItemFailure(new ProductId("SKU-005"), 1, 0, FailureKind.INSUFFICIENT_STOCK))),
                RESERVED_AT,
                RESERVED_AT);
        Map<ProductId, StockItem> stock = stock("SKU-005", 0);

        ReleaseOutcome outcome = rejected.release(stock, NOW);

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOTHING_RESERVED);
        assertThat(stock.get(new ProductId("SKU-005")).availableQuantity()).isZero();
        assertThat(rejected.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(rejected.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("should never give stock back twice for an already RELEASED reservation")
    void shouldNotReleaseTwice() {
        Reservation released = Reservation.restoreReleased(
                RESERVATION_ID, ORDER_ID, items(line("SKU-001", 3)), RESERVED_AT, RESERVED_AT.plusSeconds(1));
        Map<ProductId, StockItem> stock = stock("SKU-001", 10);

        ReleaseOutcome outcome = released.release(stock, NOW);

        assertThat(outcome).isEqualTo(ReleaseOutcome.ALREADY_RELEASED);
        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(10);
        assertThat(released.releasedAt()).contains(RESERVED_AT.plusSeconds(1));
        assertThat(released.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("should give nothing back the second time the same reservation is released")
    void shouldReleaseOnlyOnce() {
        Reservation reservation = reserved(line("SKU-001", 3));
        Map<ProductId, StockItem> stock = stock("SKU-001", 7);

        reservation.release(stock, NOW);
        reservation.pullDomainEvents();
        ReleaseOutcome second = reservation.release(stock, NOW.plusSeconds(5));

        assertThat(second).isEqualTo(ReleaseOutcome.ALREADY_RELEASED);
        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(10);
        assertThat(reservation.pullDomainEvents()).isEmpty();
    }

    private static Reservation reserved(RequestedItem... lines) {
        return Reservation.restore(
                RESERVATION_ID, ORDER_ID, ReservationStatus.RESERVED, items(lines), RESERVED_AT, RESERVED_AT);
    }

    private static RequestedItems items(RequestedItem... lines) {
        return RequestedItems.consolidate(List.of(lines));
    }

    private static RequestedItem line(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
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
