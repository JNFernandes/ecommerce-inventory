package com.ecommerce.inventory.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OutcomeMessageJpaRepository extends JpaRepository<OutcomeMessageEntity, UUID> {}
