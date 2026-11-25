package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingAdjustmentStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface BillingAdjustmentRepository extends JpaRepository<BillingAdjustmentEntity, UUID> {

    Optional<BillingAdjustmentEntity> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Optional<BillingAdjustmentEntity> findByLateUsageRecordId(UUID lateUsageRecordId);

    List<BillingAdjustmentEntity> findBySubscriptionIdAndStatusOrderByCreatedAtAsc(
            UUID subscriptionId,
            BillingAdjustmentStatus status
    );

    List<BillingAdjustmentEntity> findByAppliedInvoiceId(UUID appliedInvoiceId);

    List<BillingAdjustmentEntity> findByOriginalInvoiceIdAndMetricId(UUID originalInvoiceId, UUID metricId);

    Page<BillingAdjustmentEntity> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);
}
