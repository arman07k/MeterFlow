package com.meterflow.catalog.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MetricFilterRepository extends JpaRepository<MetricFilterEntity, UUID> {

    List<MetricFilterEntity> findByMetricIdOrderByFilterOrderAsc(UUID metricId);

    List<MetricFilterEntity> findByMetricIdInOrderByMetricIdAscFilterOrderAsc(Collection<UUID> metricIds);

    void deleteByMetricId(UUID metricId);
}
