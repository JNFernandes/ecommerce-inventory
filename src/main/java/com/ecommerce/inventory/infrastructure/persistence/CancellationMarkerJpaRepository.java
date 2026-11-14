package com.ecommerce.inventory.infrastructure.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CancellationMarkerJpaRepository extends JpaRepository<CancellationMarkerEntity, UUID> {}
