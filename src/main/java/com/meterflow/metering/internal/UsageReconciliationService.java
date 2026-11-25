package com.meterflow.metering.internal;

import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.metering.api.MeteringResourceNotFoundException;
import com.meterflow.metering.api.RollupReconciliationResult;
import com.meterflow.metering.api.UsageReconciliationFacade;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACID rollup reconciliation and crash-recovery service recomputing hourly rollups
 * deterministically from immutable {@code usage_events} (QA1, QA3, ADR-013).
 */
@Service
class UsageReconciliationService implements UsageReconciliationFacade {

    private static final int MAX_RECOVERY_BATCH = 5_000;

    private final MeteringBatchJdbcRepository batchJdbcRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final MetricCatalogFacade metricCatalogFacade;
    private final MeteringCacheHelper cacheHelper;
    private final MeteringMetrics metrics;
    private final Clock clock;

    UsageReconciliationService(
            MeteringBatchJdbcRepository batchJdbcRepository,
            IdentityQueryFacade identityQueryFacade,
            MetricCatalogFacade metricCatalogFacade,
            MeteringCacheHelper cacheHelper,
            MeteringMetrics metrics,
            Clock clock
    ) {
        this.batchJdbcRepository = batchJdbcRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.metricCatalogFacade = metricCatalogFacade;
        this.cacheHelper = cacheHelper;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RollupReconciliationResult reconcileRollups(
            UUID customerId,
            UUID metricId,
            Instant windowStart,
            Instant windowEnd
    ) {
        if (customerId == null || metricId == null || windowStart == null || windowEnd == null) {
            throw new InvalidMeteringRequestException("customerId, metricId, windowStart, and windowEnd are required");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw new InvalidMeteringRequestException("windowEnd must be strictly after windowStart");
        }
        if (identityQueryFacade.findCustomerById(customerId).isEmpty()) {
            throw new IdentityResourceNotFoundException("Customer not found");
        }
        if (metricCatalogFacade.findMetricById(metricId).isEmpty()) {
            throw new MeteringResourceNotFoundException("Billable metric not found");
        }

        long startNanos = System.nanoTime();
        Instant fromBucket = windowStart.truncatedTo(ChronoUnit.HOURS);
        Instant endTrunc = windowEnd.truncatedTo(ChronoUnit.HOURS);
        Instant toBucket = windowEnd.equals(endTrunc) ? endTrunc : endTrunc.plus(1, ChronoUnit.HOURS);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        MeteringBatchJdbcRepository.ReconcileStats stats = batchJdbcRepository
                .replaceRollupsFromAuthoritativeEvents(customerId, metricId, fromBucket, toBucket, now);

        cacheHelper.evictLiveEstimatesAfterCommit(List.of(customerId));
        metrics.recordRollupReconciliation();
        metrics.recordRollupDuration(Duration.ofNanos(System.nanoTime() - startNanos));

        return new RollupReconciliationResult(
                customerId,
                metricId,
                fromBucket,
                toBucket,
                stats.bucketsReconciled(),
                stats.eventsProcessed(),
                stats.reconciledSum(),
                stats.matchedCount(),
                now
        );
    }

    @Override
    @Transactional
    public int recoverPendingRollups(int maxEvents) {
        int clamped = Math.clamp(maxEvents, 1, MAX_RECOVERY_BATCH);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return batchJdbcRepository.recoverPendingRollups(clamped, now);
    }
}
