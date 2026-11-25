package com.meterflow.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MinimumCommitmentRepository extends JpaRepository<MinimumCommitmentEntity, UUID> {

    List<MinimumCommitmentEntity> findByPlanVersionIdOrderByCreatedAtAsc(UUID planVersionId);

    List<MinimumCommitmentEntity> findByPlanVersionIdInOrderByPlanVersionIdAscCreatedAtAsc(Collection<UUID> planVersionIds);

    void deleteByPlanVersionId(UUID planVersionId);
}
