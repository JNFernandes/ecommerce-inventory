package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.port.OrderLock;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.infrastructure.config.InventoryProperties;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-order lock with a transaction-scoped PostgreSQL advisory lock (research R1, ADR 0005). The key is
 * the order UUID folded to 64 bits; a collision only serialises two unrelated orders briefly. The wait
 * is bounded by {@code inventory.stock.lock-timeout}; a timeout surfaces as a
 * {@link org.springframework.dao.PessimisticLockingFailureException}, like the stock row locks, and goes
 * through the normal retry path.
 */
@Repository
public class PostgresOrderLock implements OrderLock {

    private final EntityManager entityManager;
    private final Duration lockTimeout;

    PostgresOrderLock(EntityManager entityManager, InventoryProperties properties) {
        this.entityManager = entityManager;
        this.lockTimeout = properties.stock().lockTimeout();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(OrderId orderId) {
        entityManager
                .createNativeQuery("select set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeout.toMillis() + "ms")
                .getSingleResult();
        entityManager
                .createNativeQuery("select cast(pg_advisory_xact_lock(:key) as text)")
                .setParameter("key", key(orderId.value()))
                .getSingleResult();
    }

    static long key(UUID orderId) {
        return orderId.getMostSignificantBits() ^ orderId.getLeastSignificantBits();
    }
}
