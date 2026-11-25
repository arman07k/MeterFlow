package com.meterflow.metering.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UsageThresholdEvaluationRepository extends JpaRepository<UsageThresholdEvaluationEntity, UUID> {

    @Query("""
            SELECT t FROM UsageThresholdEvaluationEntity t
            WHERE t.customerId = :customerId
              AND t.windowStart >= :windowStart
              AND t.windowEnd <= :windowEnd
            ORDER BY t.triggeredAt ASC, t.thresholdPercent ASC
            """)
    List<UsageThresholdEvaluationEntity> findByCustomerAndWindow(
            @Param("customerId") UUID customerId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd
    );

    List<UsageThresholdEvaluationEntity> findByCustomerIdAndScopeKeyAndWindowStartAndWindowEndOrderByThresholdPercentAsc(
            UUID customerId,
            String scopeKey,
            Instant windowStart,
            Instant windowEnd
    );
}
