package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutcomeMessageRepository;
import com.ecommerce.inventory.domain.model.ReservationId;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Stores the outcome message of each reservation in {@code outcome_messages}. */
@Repository
public class OutcomeMessageRepositoryAdapter implements OutcomeMessageRepository {

    private final OutcomeMessageJpaRepository jpaRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    OutcomeMessageRepositoryAdapter(
            OutcomeMessageJpaRepository jpaRepository, EntityManager entityManager, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public void save(ReservationId reservationId, OutboundMessage message) {
        entityManager.persist(new OutcomeMessageEntity(
                message.eventId(),
                reservationId.value(),
                message.topic(),
                message.key(),
                message.payload(),
                clock.instant()));
    }

    @Override
    public Optional<OutboundMessage> findByEventId(UUID eventId) {
        return jpaRepository
                .findById(eventId)
                .map(row ->
                        new OutboundMessage(row.getEventId(), row.getTopic(), row.getMessageKey(), row.getPayload()));
    }
}
