package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V3 migration: released reservations, two outcomes per reservation, processed events, markers. */
class ReleaseSchemaMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    @Test
    @DisplayName("should require released_at exactly when the reservation is RELEASED")
    void shouldRequireReleasedAtIffReleased() {
        assertThatThrownBy(() -> insertReservation("RELEASED", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservations_released_at_iff_released");
        assertThatThrownBy(() -> insertReservation("RESERVED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_reservations_released_at_iff_released");
        insertReservation("RELEASED", true);
    }

    @Test
    @DisplayName("should allow a reserve and a release outcome message for the same reservation")
    void shouldAllowTwoOutcomesPerReservation() {
        UUID reservation = insertReservation("RELEASED", true);

        insertOutcome(UUID.randomUUID(), reservation);
        insertOutcome(UUID.randomUUID(), reservation);

        assertThat(jdbc.queryForObject("select count(*) from outcome_messages", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("should require an outcome message for STOCK_RELEASED and none for ignored cancellations")
    void shouldLinkOutcomeOnlyForPublishingEvents() {
        UUID reservation = insertReservation("RELEASED", true);

        assertThatThrownBy(() -> insertProcessed("STOCK_RELEASED", reservation, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_processed_events_outcome_message");
        insertProcessed("CANCELLATION_RECORDED", null, null);
        insertProcessed("PLACEMENT_CANCELLED", null, null);
        UUID outcome = UUID.randomUUID();
        insertOutcome(outcome, reservation);
        insertProcessed("STOCK_RELEASED", reservation, outcome);
    }

    @Test
    @DisplayName("should keep at most one cancellation marker per order")
    void shouldKeepOneMarkerPerOrder() {
        UUID orderId = UUID.randomUUID();
        jdbc.update(
                "insert into cancellation_markers (order_id, event_id, created_at) values (?, ?, now())",
                orderId,
                UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update(
                        "insert into cancellation_markers (order_id, event_id, created_at) values (?, ?, now())",
                        orderId,
                        UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("should backfill outcome_event_id for reservations decided before V3")
    void shouldBackfillOutcomeEventIdOfExistingRows() {
        String schema = "v3_backfill_" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000);
        Flyway atV2 = flyway(schema, "2");
        atV2.migrate();
        JdbcTemplate scoped = new JdbcTemplate(dataSource);
        UUID reservation = UUID.randomUUID();
        UUID outcome = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        try {
            scoped.update(
                    "insert into " + schema + ".reservations (id, order_id, status, created_at, updated_at)"
                            + " values (?, ?, 'RESERVED', now(), now())",
                    reservation,
                    UUID.randomUUID());
            scoped.update(
                    "insert into " + schema + ".outcome_messages"
                            + " (event_id, reservation_id, topic, message_key, payload, created_at)"
                            + " values (?, ?, 'inventory.stock-reserved', 'k', '{}'::jsonb, now())",
                    outcome,
                    reservation);
            scoped.update(
                    "insert into " + schema + ".processed_events"
                            + " (event_id, topic, outcome, reservation_id, processed_at)"
                            + " values (?, 'orders.order-placed', 'RESERVATION_DECIDED', ?, now())",
                    event,
                    reservation);

            flyway(schema, "latest").migrate();

            assertThat(scoped.queryForObject(
                            "select outcome_event_id from " + schema + ".processed_events where event_id = ?",
                            UUID.class,
                            event))
                    .isEqualTo(outcome);
        } finally {
            scoped.execute("drop schema if exists " + schema + " cascade");
        }
    }

    private Flyway flyway(String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private UUID insertReservation(String status, boolean released) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into reservations (id, order_id, status, created_at, updated_at, released_at)"
                        + " values (?, ?, ?, now(), now(), " + (released ? "now()" : "null") + ")",
                id,
                UUID.randomUUID(),
                status);
        return id;
    }

    private void insertOutcome(UUID eventId, UUID reservation) {
        jdbc.update(
                "insert into outcome_messages (event_id, reservation_id, topic, message_key, payload, created_at)"
                        + " values (?, ?, 'inventory.stock-released', 'k', '{}'::jsonb, now())",
                eventId,
                reservation);
    }

    private void insertProcessed(String outcome, UUID reservation, UUID outcomeEventId) {
        jdbc.update(
                "insert into processed_events"
                        + " (event_id, topic, outcome, reservation_id, outcome_event_id, processed_at)"
                        + " values (?, 'orders.order-cancelled', ?, ?, ?, now())",
                UUID.randomUUID(),
                outcome,
                reservation,
                outcomeEventId);
    }
}
