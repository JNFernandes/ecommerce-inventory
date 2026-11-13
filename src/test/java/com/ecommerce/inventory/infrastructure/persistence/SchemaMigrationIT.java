package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    @Test
    @DisplayName("should apply the baseline and the reserve-stock migrations")
    void shouldApplyMigrations() {
        List<String> versions = jdbc.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class);

        assertThat(versions).containsSubsequence("1", "2");
    }

    @Test
    @DisplayName("should refuse a negative available quantity (FR-009)")
    void shouldRefuseNegativeStock() {
        assertThatThrownBy(() ->
                        jdbc.update("insert into stock_items (product_id, available_quantity) values ('SKU-001', -1)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_stock_items_available_not_negative");
    }

    @Test
    @DisplayName("should refuse a second reservation for the same order (FR-013)")
    void shouldRefuseSecondReservationForOrder() {
        UUID orderId = UUID.randomUUID();
        insertReservation(UUID.randomUUID(), orderId, "RESERVED", null);

        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), orderId, "RESERVED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_reservations_order_id");
    }

    @Test
    @DisplayName("should refuse a rejected reservation without a reason")
    void shouldRefuseRejectedWithoutReason() {
        assertThatThrownBy(() -> insertReservation(UUID.randomUUID(), UUID.randomUUID(), "REJECTED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservations_reason_iff_rejected");
    }

    @Test
    @DisplayName("should refuse a reserved reservation with a rejection reason")
    void shouldRefuseReservedWithReason() {
        assertThatThrownBy(
                        () -> insertReservation(UUID.randomUUID(), UUID.randomUUID(), "RESERVED", "insufficient stock"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservations_reason_iff_rejected");
    }

    @Test
    @DisplayName("should keep one consumed dead letter per topic, partition and offset")
    void shouldRefuseDuplicateConsumedDeadLetter() {
        insertConsumedDeadLetter();

        assertThatThrownBy(this::insertConsumedDeadLetter)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_consumed_dead_letters_record");
    }

    private void insertReservation(UUID id, UUID orderId, String status, String reason) {
        jdbc.update(
                "insert into reservations (id, order_id, status, rejection_reason, created_at, updated_at)"
                        + " values (?, ?, ?, ?, now(), now())",
                id,
                orderId,
                status,
                reason);
    }

    private void insertConsumedDeadLetter() {
        jdbc.update("insert into consumed_dead_letters"
                + " (id, topic, partition, record_offset, error, attempts, failed_at)"
                + " values (gen_random_uuid(), 'orders.order-placed', 0, 42, 'boom', 1, now())");
    }
}
