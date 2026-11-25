package com.meterflow.catalog.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PlanVersionRepository extends JpaRepository<PlanVersionEntity, UUID> {

    @Query("SELECT pv FROM PlanVersionEntity pv JOIN FETCH pv.plan WHERE pv.id = :id")
    Optional<PlanVersionEntity> findByIdWithPlan(@Param("id") UUID id);

    @Query("SELECT pv FROM PlanVersionEntity pv JOIN FETCH pv.plan WHERE pv.plan.id = :planId ORDER BY pv.versionNumber DESC")
    List<PlanVersionEntity> findByPlanIdWithPlanOrderByVersionNumberDesc(@Param("planId") UUID planId);

    @Query("SELECT COALESCE(MAX(pv.versionNumber), 0) FROM PlanVersionEntity pv WHERE pv.plan.id = :planId")
    int findMaxVersionNumberByPlanId(@Param("planId") UUID planId);
}
