package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.port.ProcessedEvent;
import com.ecommerce.inventory.application.port.ProcessedEventRepository;
import com.ecommerce.inventory.domain.model.ReservationId;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Stores processed inbound event ids in {@code processed_events} (FR-015). */
@Repository
public class ProcessedEventRepositoryAdapter implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository jpaRepository;
    private final EntityManager entityManager;

    ProcessedEventRepositoryAdapter(ProcessedEventJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<ProcessedEvent> find(UUID eventId) {
        return jpaRepository
                .findById(eventId)
                .map(row -> new ProcessedEvent(
                        row.getEventId(),
                        row.getTopic(),
                        row.getOutcome(),
                        new ReservationId(row.getReservationId()),
                        row.getProcessedAt()));
    }

    @Override
    public void save(ProcessedEvent event) {
        entityManager.persist(new ProcessedEventEntity(
                event.eventId(),
                event.topic(),
                event.outcome(),
                event.reservationId().value(),
                event.processedAt()));
    }
}
