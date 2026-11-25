package com.meterflow.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface BillableMetricRepository extends JpaRepository<BillableMetricEntity, UUID> {

    Optional<BillableMetricEntity> findByMetricKeyAndCustomerIdIsNull(String metricKey);

    Optional<BillableMetricEntity> findByMetricKeyAndCustomerId(String metricKey, UUID customerId);

    boolean existsByMetricKeyAndCustomerIdIsNull(String metricKey);

    boolean existsByMetricKeyAndCustomerId(String metricKey, UUID customerId);

    Page<BillableMetricEntity> findAllByCustomerIdIsNullOrCustomerId(UUID customerId, Pageable pageable);
}
