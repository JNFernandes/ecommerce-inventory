package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.ecommerce.inventory.domain.repository.ReservationRepository;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class ReservationRepositoryAdapterIT extends AbstractPostgresIT {

    @Autowired
    private ReservationRepository repository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    @Test
    @DisplayName("should save a reserved reservation with its items and find it by order id")
    void shouldRoundTripReservedReservation() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = Reservation.restore(
                ReservationId.newId(),
                new OrderId(UUID.randomUUID()),
                ReservationStatus.RESERVED,
                RequestedItems.consolidate(List.of(item("SKU-002", 5), item("SKU-001", 3))),
                now,
                now);

        transactions.executeWithoutResult(status -> repository.save(reservation));

        assertThat(repository.findByOrderId(reservation.orderId())).hasValueSatisfying(found -> {
            assertThat(found.id()).isEqualTo(reservation.id());
            assertThat(found.status()).isEqualTo(ReservationStatus.RESERVED);
            assertThat(found.items()).isEqualTo(reservation.items());
            assertThat(found.createdAt()).isEqualTo(now);
        });
        assertThat(jdbc.queryForObject(
                        "select count(*) from reservation_items where reservation_id = ?",
                        Integer.class,
                        reservation.id().value()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("should return empty for an order without reservation")
    void shouldReturnEmptyForUnknownOrder() {
        assertThat(repository.findByOrderId(new OrderId(UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("should save and restore a rejected reservation with reason and failing items")
    void shouldRoundTripRejectedReservation() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Rejection rejection = new Rejection(
                RejectionReason.UNKNOWN_PRODUCT_AND_INSUFFICIENT_STOCK,
                List.of(
                        new ItemFailure(new ProductId("SKU-004"), 6, 5, FailureKind.INSUFFICIENT_STOCK),
                        new ItemFailure(new ProductId("SKU-999"), 1, 0, FailureKind.UNKNOWN_PRODUCT)));
        Reservation reservation = Reservation.restoreRejected(
                ReservationId.newId(),
                new OrderId(UUID.randomUUID()),
                RequestedItems.consolidate(List.of(item("SKU-001", 2), item("SKU-004", 6), item("SKU-999", 1))),
                rejection,
                now,
                now);

        transactions.executeWithoutResult(status -> repository.save(reservation));

        assertThat(repository.findByOrderId(reservation.orderId())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(ReservationStatus.REJECTED);
            assertThat(found.items()).isEqualTo(reservation.items());
            assertThat(found.rejection()).contains(rejection);
        });
        assertThat(jdbc.queryForList(
                        "select product_id || ':' || coalesce(failure_kind, '-') || ':'"
                                + " || coalesce(available_at_decision::text, '-')"
                                + " from reservation_items where reservation_id = ? order by product_id",
                        String.class,
                        reservation.id().value()))
                .containsExactly("SKU-001:-:-", "SKU-004:INSUFFICIENT_STOCK:5", "SKU-999:UNKNOWN_PRODUCT:0");
    }

    @Test
    @DisplayName("should save and restore a released reservation with its release time")
    void shouldRoundTripReleasedReservation() {
        Instant created = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant released = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Reservation reservation = Reservation.restoreReleased(
                ReservationId.newId(),
                new OrderId(UUID.randomUUID()),
                RequestedItems.consolidate(List.of(item("SKU-001", 3))),
                created,
                released);

        transactions.executeWithoutResult(status -> repository.save(reservation));

        assertThat(repository.findByOrderId(reservation.orderId())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(found.releasedAt()).contains(released);
            assertThat(found.updatedAt()).isEqualTo(released);
        });
    }

    @Test
    @DisplayName("should update a reserved reservation to released")
    void shouldUpdateReservedToReleased() {
        Instant created = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        Instant released = Instant.now().truncatedTo(ChronoUnit.MICROS);
        ProductId sku = new ProductId("SKU-001");
        Reservation reservation = Reservation.restore(
                ReservationId.newId(),
                new OrderId(UUID.randomUUID()),
                ReservationStatus.RESERVED,
                RequestedItems.consolidate(List.of(item("SKU-001", 3))),
                created,
                created);
        transactions.executeWithoutResult(status -> repository.save(reservation));

        transactions.executeWithoutResult(status -> {
            Reservation loaded = repository.findByOrderId(reservation.orderId()).orElseThrow();
            loaded.release(java.util.Map.of(sku, new com.ecommerce.inventory.domain.model.StockItem(sku, 7)), released);
            repository.update(loaded);
        });

        assertThat(repository.findByOrderId(reservation.orderId())).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(found.releasedAt()).contains(released);
            assertThat(found.createdAt()).isEqualTo(created);
        });
    }

    private static RequestedItem item(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }
}
