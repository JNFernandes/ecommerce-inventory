package com.ecommerce.inventory.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, UUID> {}
