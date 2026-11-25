package com.meterflow.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface FreeAllowanceRepository extends JpaRepository<FreeAllowanceEntity, UUID> {

    List<FreeAllowanceEntity> findByPlanVersionIdOrderByCreatedAtAsc(UUID planVersionId);

    List<FreeAllowanceEntity> findByPlanVersionIdInOrderByPlanVersionIdAscCreatedAtAsc(Collection<UUID> planVersionIds);

    void deleteByPlanVersionId(UUID planVersionId);
}
