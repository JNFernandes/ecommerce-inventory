package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V4 migration: {@code resolved_at} is set exactly when an outbound dead letter is RESOLVED. */
class OutboundRetryMigrationIT extends AbstractPostgresIT {

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    @Test
    @DisplayName("should refuse a RESOLVED row without resolved_at")
    void shouldRefuseResolvedWithoutResolvedAt() {
        assertThatThrownBy(() -> insert("RESOLVED", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbound_dead_letters_resolved_at_iff_resolved");
    }

    @Test
    @DisplayName("should refuse a PENDING row with resolved_at")
    void shouldRefusePendingWithResolvedAt() {
        assertThatThrownBy(() -> insert("PENDING", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbound_dead_letters_resolved_at_iff_resolved");
    }

    @Test
    @DisplayName("should accept RESOLVED with resolved_at and PENDING or EXHAUSTED without it")
    void shouldAcceptConsistentRows() {
        assertThatCode(() -> {
                    insert("RESOLVED", true);
                    insert("PENDING", false);
                    insert("EXHAUSTED", false);
                })
                .doesNotThrowAnyException();
    }

    private void insert(String status, boolean resolved) {
        jdbc.update(
                "insert into outbound_dead_letters (id, event_id, topic, message_key, payload, status, retry_count,"
                        + " next_retry_at, last_error, created_at, updated_at, resolved_at)"
                        + " values (?, ?, 'inventory.stock-reserved', 'k', '{}'::jsonb, ?, 0, now(), 'e',"
                        + " now(), now(), "
                        + (resolved ? "now()" : "null") + ")",
                UUID.randomUUID(),
                UUID.randomUUID(),
                status);
    }
}
