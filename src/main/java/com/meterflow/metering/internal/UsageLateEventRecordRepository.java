package com.meterflow.metering.internal;

import com.meterflow.metering.api.LateAdjustmentStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface UsageLateEventRecordRepository extends JpaRepository<UsageLateEventRecordEntity, UUID> {

    Page<UsageLateEventRecordEntity> findByCustomerIdAndAdjustmentStatusOrderByCreatedAtAsc(
            UUID customerId,
            LateAdjustmentStatus adjustmentStatus,
            Pageable pageable
    );

    Page<UsageLateEventRecordEntity> findByAdjustmentStatusOrderByCreatedAtAsc(
            LateAdjustmentStatus adjustmentStatus,
            Pageable pageable
    );
}
