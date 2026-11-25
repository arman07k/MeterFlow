package com.meterflow.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PlanChargeRepository extends JpaRepository<PlanChargeEntity, UUID> {

    List<PlanChargeEntity> findByPlanVersionIdOrderByDisplayOrderAsc(UUID planVersionId);

    List<PlanChargeEntity> findByPlanVersionIdInOrderByPlanVersionIdAscDisplayOrderAsc(Collection<UUID> planVersionIds);

    void deleteByPlanVersionId(UUID planVersionId);
}
