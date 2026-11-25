package com.meterflow.metering.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UsageQueryFacade {

    UsageAggregationResult queryMetricUsage(UsageQueryRequest request);

    LiveUsageEstimateResult estimateLiveUsage(LiveUsageEstimateRequest request);

    Page<UsageEventRecord> listCustomerEvents(
            UUID customerId,
            UUID metricId,
            Instant windowStart,
            Instant windowEnd,
            Pageable pageable
    );

    Optional<UsageEventRecord> findEventById(UUID eventId);
}
