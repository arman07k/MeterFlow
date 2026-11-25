package com.meterflow.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChargeTierRepository extends JpaRepository<ChargeTierEntity, UUID> {

    List<ChargeTierEntity> findByPlanChargeIdInOrderByPlanChargeIdAscTierOrderAsc(Collection<UUID> planChargeIds);

    void deleteByPlanChargeIdIn(Collection<UUID> planChargeIds);
}
