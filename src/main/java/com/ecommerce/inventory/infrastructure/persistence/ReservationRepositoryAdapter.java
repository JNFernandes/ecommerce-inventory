package com.ecommerce.inventory.infrastructure.persistence;

import com.ecommerce.inventory.domain.model.OrderId;
import com.ecommerce.inventory.domain.model.Reservation;
import com.ecommerce.inventory.domain.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Reservation persistence. The unique {@code order_id} constraint backs FR-013. */
@Repository
public class ReservationRepositoryAdapter implements ReservationRepository {

    private final ReservationJpaRepository jpaRepository;
    private final EntityManager entityManager;

    ReservationRepositoryAdapter(ReservationJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Reservation> findByOrderId(OrderId orderId) {
        return jpaRepository.findByOrderId(orderId.value()).map(ReservationMapper::toDomain);
    }

    @Override
    public void save(Reservation reservation) {
        entityManager.persist(ReservationMapper.toEntity(reservation));
    }

    @Override
    public void update(Reservation reservation) {
        ReservationEntity entity =
                entityManager.find(ReservationEntity.class, reservation.id().value());
        if (entity == null) {
            throw new IllegalStateException("Reservation " + reservation.id() + " does not exist");
        }
        ReservationMapper.applyState(entity, reservation);
    }
}
