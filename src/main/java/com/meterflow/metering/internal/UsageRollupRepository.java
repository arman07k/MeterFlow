package com.meterflow.metering.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UsageRollupRepository extends JpaRepository<UsageRollupEntity, UUID> {

    @Query("""
            SELECT r FROM UsageRollupEntity r
            WHERE r.customerId = :customerId
              AND r.metricId = :metricId
              AND r.bucketStart >= :fromBucket
              AND r.bucketStart < :toBucket
            ORDER BY r.bucketStart ASC
            """)
    List<UsageRollupEntity> findBucketsInRange(
            @Param("customerId") UUID customerId,
            @Param("metricId") UUID metricId,
            @Param("fromBucket") Instant fromBucket,
            @Param("toBucket") Instant toBucket
    );
}
