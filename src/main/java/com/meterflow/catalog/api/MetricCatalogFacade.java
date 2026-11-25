package com.meterflow.catalog.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MetricCatalogFacade {

    BillableMetricDefinition createMetric(CreateMetricCommand command);

    BillableMetricDefinition updateMetric(UUID metricId, UpdateMetricCommand command);

    Optional<BillableMetricDefinition> findMetricById(UUID metricId);

    Map<UUID, BillableMetricDefinition> findMetricsByIds(Collection<UUID> metricIds);

    Optional<BillableMetricDefinition> findMetricByKey(String metricKey, UUID customerId);

    Page<BillableMetricDefinition> listMetrics(Pageable pageable);

    Page<BillableMetricDefinition> listCustomerAccessibleMetrics(UUID customerId, Pageable pageable);
}
