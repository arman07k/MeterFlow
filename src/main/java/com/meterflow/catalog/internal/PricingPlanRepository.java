package com.meterflow.catalog.internal;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PricingPlanRepository extends JpaRepository<PricingPlanEntity, UUID> {

    boolean existsByPlanCode(String planCode);

    Optional<PricingPlanEntity> findByPlanCode(String planCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PricingPlanEntity p WHERE p.id = :planId")
    Optional<PricingPlanEntity> findByIdForUpdate(@Param("planId") UUID planId);
}
