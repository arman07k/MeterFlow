package com.meterflow.metering.internal;

import com.meterflow.metering.api.LateAdjustmentStatus;
import com.meterflow.metering.api.LateUsageEventRecord;
import com.meterflow.metering.api.LateUsageQueryFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LateUsageQueryService implements LateUsageQueryFacade {

    private final UsageLateEventRecordRepository lateEventRepository;
    private final MeteringBatchJdbcRepository batchJdbcRepository;
    private final Clock clock;

    LateUsageQueryService(
            UsageLateEventRecordRepository lateEventRepository,
            MeteringBatchJdbcRepository batchJdbcRepository,
            Clock clock
    ) {
        this.lateEventRepository = lateEventRepository;
        this.batchJdbcRepository = batchJdbcRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LateUsageEventRecord> listPendingLateEvents(UUID customerId, Pageable pageable) {
        Page<UsageLateEventRecordEntity> page = customerId == null
                ? lateEventRepository.findByAdjustmentStatusOrderByCreatedAtAsc(LateAdjustmentStatus.PENDING_BILLING, pageable)
                : lateEventRepository.findByCustomerIdAndAdjustmentStatusOrderByCreatedAtAsc(
                        customerId, LateAdjustmentStatus.PENDING_BILLING, pageable);
        return page.map(LateUsageQueryService::toRecord);
    }

    @Override
    @Transactional
    public int markLateEventsConsumed(Collection<UUID> lateRecordIds) {
        if (lateRecordIds == null || lateRecordIds.isEmpty()) {
            return 0;
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return batchJdbcRepository.markLateEventsConsumed(lateRecordIds, now);
    }

    private static LateUsageEventRecord toRecord(UsageLateEventRecordEntity entity) {
        return new LateUsageEventRecord(
                entity.getId(),
                entity.getUsageEventId(),
                entity.getCustomerId(),
                entity.getSubscriptionId(),
                entity.getMetricId(),
                entity.getEventTimestamp(),
                entity.getIngestedAt(),
                entity.getClosedPeriodStart(),
                entity.getClosedPeriodEnd(),
                entity.getHoursLate(),
                entity.getAdjustmentStatus(),
                entity.getCreatedAt(),
                entity.getConsumedAt()
        );
    }
}
