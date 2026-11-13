package com.ecommerce.inventory.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.domain.event.InventoryDomainEvent;
import com.ecommerce.inventory.domain.event.StockRejected;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationRejectTest {

    private static final ReservationId RESERVATION_ID = ReservationId.newId();
    private static final OrderId ORDER_ID = new OrderId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-13T10:16:02.001Z");

    @Test
    @DisplayName("should reject with insufficient stock and list only the insufficient item")
    void shouldRejectInsufficientStock() {
        Map<ProductId, StockItem> stock = stock("SKU-001", 10, "SKU-004", 5);

        Reservation reservation = decide(stock, line("SKU-001", 2), line("SKU-004", 6));

        assertThat(reservation.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(reservation.rejection()).hasValueSatisfying(rejection -> {
            assertThat(rejection.reason()).isEqualTo(RejectionReason.INSUFFICIENT_STOCK);
            assertThat(rejection.failures()).containsExactly(failure("SKU-004", 6, 5, FailureKind.INSUFFICIENT_STOCK));
        });
    }

    @Test
    @DisplayName("should treat a product with zero stock as insufficient, not unknown")
    void shouldTreatZeroStockAsInsufficient() {
        Reservation reservation = decide(stock("SKU-005", 0), line("SKU-005", 1));

        assertThat(reservation.rejection()).hasValueSatisfying(rejection -> {
            assertThat(rejection.reason()).isEqualTo(RejectionReason.INSUFFICIENT_STOCK);
            assertThat(rejection.failures()).containsExactly(failure("SKU-005", 1, 0, FailureKind.INSUFFICIENT_STOCK));
        });
    }

    @Test
    @DisplayName("should reject an unknown product with available 0")
    void shouldRejectUnknownProduct() {
        Reservation reservation = decide(stock("SKU-001", 10), line("SKU-001", 1), line("SKU-999", 2));

        assertThat(reservation.rejection()).hasValueSatisfying(rejection -> {
            assertThat(rejection.reason()).isEqualTo(RejectionReason.UNKNOWN_PRODUCT);
            assertThat(rejection.failures()).containsExactly(failure("SKU-999", 2, 0, FailureKind.UNKNOWN_PRODUCT));
        });
    }

    @Test
    @DisplayName("should reject with the combined reason and list both kinds of failures, sorted by product id")
    void shouldRejectMixedFailures() {
        Reservation reservation = decide(stock("SKU-004", 5), line("SKU-999", 1), line("SKU-004", 6));

        assertThat(reservation.rejection()).hasValueSatisfying(rejection -> {
            assertThat(rejection.reason()).isEqualTo(RejectionReason.UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK);
            assertThat(rejection.failures())
                    .containsExactly(
                            failure("SKU-004", 6, 5, FailureKind.INSUFFICIENT_STOCK),
                            failure("SKU-999", 1, 0, FailureKind.UNKNOWN_PRODUCT));
        });
    }

    @Test
    @DisplayName("should check the consolidated requested quantity")
    void shouldUseConsolidatedQuantity() {
        Reservation reservation = decide(stock("SKU-001", 10), line("SKU-001", 6), line("SKU-001", 7));

        assertThat(reservation.rejection())
                .hasValueSatisfying(rejection -> assertThat(rejection.failures())
                        .containsExactly(failure("SKU-001", 13, 10, FailureKind.INSUFFICIENT_STOCK)));
    }

    @Test
    @DisplayName("should reject, not throw, when the consolidated quantity exceeds Integer.MAX_VALUE")
    void shouldRejectHugeConsolidatedQuantity() {
        Reservation reservation = decide(
                stock("SKU-001", Integer.MAX_VALUE),
                line("SKU-001", Integer.MAX_VALUE),
                line("SKU-001", Integer.MAX_VALUE));

        assertThat(reservation.rejection()).hasValueSatisfying(rejection -> {
            assertThat(rejection.reason()).isEqualTo(RejectionReason.INSUFFICIENT_STOCK);
            assertThat(rejection.failures().getFirst().requested()).isEqualTo(2L * Integer.MAX_VALUE);
        });
    }

    @Test
    @DisplayName("should leave every stock item untouched when rejecting")
    void shouldNotTouchStock() {
        Map<ProductId, StockItem> stock = stock("SKU-001", 10, "SKU-004", 5);

        decide(stock, line("SKU-001", 2), line("SKU-004", 6));

        assertThat(stock.get(new ProductId("SKU-001")).availableQuantity()).isEqualTo(10);
        assertThat(stock.get(new ProductId("SKU-004")).availableQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("should raise exactly one StockRejected event with reason and failures")
    void shouldRaiseStockRejected() {
        Reservation reservation = decide(stock("SKU-004", 5), line("SKU-004", 6));

        List<InventoryDomainEvent> events = reservation.pullDomainEvents();

        assertThat(events)
                .containsExactly(new StockRejected(
                        RESERVATION_ID,
                        ORDER_ID,
                        RejectionReason.INSUFFICIENT_STOCK,
                        List.of(failure("SKU-004", 6, 5, FailureKind.INSUFFICIENT_STOCK)),
                        NOW));
        assertThat(events.getFirst().occurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("should have no rejection when reserved")
    void shouldHaveNoRejectionWhenReserved() {
        assertThat(decide(stock("SKU-001", 10), line("SKU-001", 1)).rejection()).isEmpty();
    }

    @Test
    @DisplayName("should restore a rejected reservation with its rejection and without events")
    void shouldRestoreRejected() {
        Rejection rejection = new Rejection(
                RejectionReason.UNKNOWN_PRODUCT, List.of(failure("SKU-999", 1, 0, FailureKind.UNKNOWN_PRODUCT)));

        Reservation restored = Reservation.restoreRejected(
                RESERVATION_ID, ORDER_ID, RequestedItems.consolidate(List.of(line("SKU-999", 1))), rejection, NOW, NOW);

        assertThat(restored.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(restored.rejection()).contains(rejection);
        assertThat(restored.pullDomainEvents()).isEmpty();
    }

    private static Reservation decide(Map<ProductId, StockItem> stock, RequestedItem... lines) {
        return Reservation.decide(RESERVATION_ID, ORDER_ID, RequestedItems.consolidate(List.of(lines)), stock, NOW);
    }

    private static ItemFailure failure(String productId, long requested, int available, FailureKind kind) {
        return new ItemFailure(new ProductId(productId), requested, available, kind);
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
