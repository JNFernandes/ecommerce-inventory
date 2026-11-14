package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.CancellationMarker;
import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.repository.CancellationMarkerRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

/** Stores cancellation markers in {@code cancellation_markers}; the primary key keeps one per order. */
@Repository
public class CancellationMarkerRepositoryAdapter implements CancellationMarkerRepository {

    private final CancellationMarkerJpaRepository jpaRepository;
    private final EntityManager entityManager;

    CancellationMarkerRepositoryAdapter(CancellationMarkerJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public boolean exists(OrderId orderId) {
        return jpaRepository.existsById(orderId.value());
    }

    @Override
    public void save(CancellationMarker marker) {
        entityManager.persist(
                new CancellationMarkerEntity(marker.orderId().value(), marker.eventId(), marker.createdAt()));
        entityManager.flush();
    }
}
