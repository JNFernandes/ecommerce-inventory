package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.ProductId;
import com.ecommerce.inventory.domain.model.Quantity;
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

    private static RequestedItem item(String productId, long quantity) {
        return new RequestedItem(new ProductId(productId), new Quantity(quantity));
    }
}
