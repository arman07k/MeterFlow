package com.meterflow.metering.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UsageEventRepository extends JpaRepository<UsageEventEntity, UUID> {

    @Query("""
            SELECT e FROM UsageEventEntity e
            WHERE e.customerId = :customerId
              AND e.eventTimestamp >= :windowStart
              AND e.eventTimestamp < :windowEnd
            ORDER BY e.eventTimestamp DESC, e.ingestedAt DESC
            """)
    Page<UsageEventEntity> findByCustomerAndWindow(
            @Param("customerId") UUID customerId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            Pageable pageable
    );

    @Query("""
            SELECT e FROM UsageEventEntity e
            WHERE e.customerId = :customerId
              AND e.metricId = :metricId
              AND e.eventTimestamp >= :windowStart
              AND e.eventTimestamp < :windowEnd
            ORDER BY e.eventTimestamp DESC, e.ingestedAt DESC
            """)
    Page<UsageEventEntity> findByCustomerMetricAndWindow(
            @Param("customerId") UUID customerId,
            @Param("metricId") UUID metricId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            Pageable pageable
    );

    @Query("""
            SELECT e FROM UsageEventEntity e
            WHERE e.customerId = :customerId
              AND e.metricId = :metricId
              AND e.eventTimestamp >= :windowStart
              AND e.eventTimestamp < :windowEnd
            ORDER BY e.eventTimestamp ASC, e.ingestedAt ASC
            """)
    Page<UsageEventEntity> findPageByCustomerMetricAndWindow(
            @Param("customerId") UUID customerId,
            @Param("metricId") UUID metricId,
            @Param("windowStart") Instant windowStart,
            @Param("windowEnd") Instant windowEnd,
            Pageable pageable
    );
}
