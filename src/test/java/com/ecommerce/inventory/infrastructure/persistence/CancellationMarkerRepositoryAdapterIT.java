package com.ecommerce.inventory.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.inventory.domain.model.CancellationMarker;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.repository.CancellationMarkerRepository;
import com.ecommerce.inventory.support.AbstractPostgresIT;
import com.ecommerce.inventory.support.DatabaseCleaner;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CancellationMarkerRepositoryAdapterIT extends AbstractPostgresIT {

    @Autowired
    private CancellationMarkerRepository markers;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        DatabaseCleaner.truncateAll(jdbc);
    }

    @Test
    @DisplayName("should find a saved marker and no marker for another order")
    void shouldSaveAndFind() {
        OrderId order = new OrderId(UUID.randomUUID());

        transactions.executeWithoutResult(
                status -> markers.save(new CancellationMarker(order, UUID.randomUUID(), Instant.now())));

        assertThat(markers.exists(order)).isTrue();
        assertThat(markers.exists(new OrderId(UUID.randomUUID()))).isFalse();
    }

    @Test
    @DisplayName("should refuse a second marker for the same order")
    void shouldKeepOneMarkerPerOrder() {
        OrderId order = new OrderId(UUID.randomUUID());
        transactions.executeWithoutResult(
                status -> markers.save(new CancellationMarker(order, UUID.randomUUID(), Instant.now())));

        assertThatThrownBy(() -> transactions.executeWithoutResult(
                        status -> markers.save(new CancellationMarker(order, UUID.randomUUID(), Instant.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
