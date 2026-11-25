package com.meterflow.metering.internal;

import com.meterflow.metering.api.EventProcessingStatus;
import com.meterflow.metering.api.LateClassification;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * High-throughput PostgreSQL JDBC UNNEST batch repository for usage event ingestion,
 * atomic idempotency conflict resolution, transactional hourly rollup upserts, and reconciliation (ADR-013).
 */
@Repository
class MeteringBatchJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    MeteringBatchJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    record CustomerIdempotencyKey(UUID customerId, String idempotencyKey) {
    }

    record PreparedUsageEvent(
            int batchIndex,
            UUID eventId,
            UUID customerId,
            UUID subscriptionId,
            UUID metricId,
            String idempotencyKey,
            String payloadHash,
            BigDecimal quantity,
            BigDecimal measuredValue,
            String distinctKey,
            Instant eventTimestamp,
            Instant ingestedAt,
            String propertiesJson,
            boolean filterMatched,
            boolean lateEvent,
            LateClassification lateClassification,
            Instant closedPeriodStart,
            Instant closedPeriodEnd,
            BigDecimal hoursLate,
            Instant billingPeriodStart,
            Instant billingPeriodEnd,
            EventProcessingStatus processingStatus
    ) {
    }

    record ExistingEventDigest(
            UUID eventId,
            UUID customerId,
            String idempotencyKey,
            String payloadHash,
            boolean lateEvent,
            String lateClassification
    ) {
    }

    record LateEventInsert(
            UUID usageEventId,
            UUID customerId,
            UUID subscriptionId,
            UUID metricId,
            Instant eventTimestamp,
            Instant ingestedAt,
            Instant closedPeriodStart,
            Instant closedPeriodEnd,
            BigDecimal hoursLate,
            Instant createdAt
    ) {
    }

    record RejectedEventInsert(
            UUID customerId,
            UUID metricId,
            String idempotencyKey,
            String rejectionCode,
            String rejectionReason,
            Instant eventTimestamp,
            String payloadHash,
            Instant rejectedAt
    ) {
    }

    record ThresholdInsertResult(
            UUID id,
            int thresholdPercent
    ) {
    }

    /**
     * Inserts prepared events in a single PostgreSQL UNNEST round-trip using
     * {@code ON CONFLICT (customer_id, idempotency_key) DO NOTHING RETURNING},
     * returning the exact set of newly inserted keys.
     */
    Set<CustomerIdempotencyKey> insertEventsIgnoreConflicts(List<PreparedUsageEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        int n = events.size();
        UUID[] ids = new UUID[n];
        UUID[] customerIds = new UUID[n];
        UUID[] subscriptionIds = new UUID[n];
        UUID[] metricIds = new UUID[n];
        String[] idempotencyKeys = new String[n];
        String[] payloadHashes = new String[n];
        BigDecimal[] quantities = new BigDecimal[n];
        BigDecimal[] measuredValues = new BigDecimal[n];
        String[] distinctKeys = new String[n];
        Timestamp[] eventTimestamps = new Timestamp[n];
        Timestamp[] ingestedAts = new Timestamp[n];
        String[] propertiesJsons = new String[n];
        Boolean[] filterMatched = new Boolean[n];
        Boolean[] lateEvents = new Boolean[n];
        String[] lateClassifications = new String[n];
        Timestamp[] periodStarts = new Timestamp[n];
        Timestamp[] periodEnds = new Timestamp[n];
        String[] processingStatuses = new String[n];

        for (int i = 0; i < n; i++) {
            PreparedUsageEvent e = events.get(i);
            ids[i] = e.eventId();
            customerIds[i] = e.customerId();
            subscriptionIds[i] = e.subscriptionId();
            metricIds[i] = e.metricId();
            idempotencyKeys[i] = e.idempotencyKey();
            payloadHashes[i] = e.payloadHash();
            quantities[i] = e.quantity();
            measuredValues[i] = e.measuredValue();
            distinctKeys[i] = e.distinctKey();
            eventTimestamps[i] = Timestamp.from(e.eventTimestamp());
            ingestedAts[i] = Timestamp.from(e.ingestedAt());
            propertiesJsons[i] = e.propertiesJson();
            filterMatched[i] = e.filterMatched();
            lateEvents[i] = e.lateEvent();
            lateClassifications[i] = e.lateClassification().name();
            periodStarts[i] = e.billingPeriodStart() == null ? null : Timestamp.from(e.billingPeriodStart());
            periodEnds[i] = e.billingPeriodEnd() == null ? null : Timestamp.from(e.billingPeriodEnd());
            processingStatuses[i] = e.processingStatus().name();
        }

        String sql = """
                INSERT INTO usage_events (
                    id, customer_id, subscription_id, metric_id, idempotency_key, payload_hash,
                    quantity, measured_value, distinct_key, event_timestamp, ingested_at,
                    properties, filter_matched, late_event, late_classification,
                    billing_period_start, billing_period_end, processing_status
                )
                SELECT
                    u.id, u.customer_id, u.subscription_id, u.metric_id, u.idempotency_key, u.payload_hash,
                    u.quantity, u.measured_value, u.distinct_key, u.event_timestamp, u.ingested_at,
                    u.properties::jsonb, u.filter_matched, u.late_event, u.late_classification,
                    u.billing_period_start, u.billing_period_end, u.processing_status
                FROM UNNEST(
                    ?::uuid[], ?::uuid[], ?::uuid[], ?::uuid[], ?::varchar[], ?::varchar[],
                    ?::numeric[], ?::numeric[], ?::varchar[], ?::timestamptz[], ?::timestamptz[],
                    ?::text[], ?::boolean[], ?::boolean[], ?::varchar[],
                    ?::timestamptz[], ?::timestamptz[], ?::varchar[]
                ) AS u(
                    id, customer_id, subscription_id, metric_id, idempotency_key, payload_hash,
                    quantity, measured_value, distinct_key, event_timestamp, ingested_at,
                    properties, filter_matched, late_event, late_classification,
                    billing_period_start, billing_period_end, processing_status
                )
                ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                RETURNING customer_id, idempotency_key
                """;

        return jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setArray(1, conn.createArrayOf("uuid", ids));
            ps.setArray(2, conn.createArrayOf("uuid", customerIds));
            ps.setArray(3, conn.createArrayOf("uuid", subscriptionIds));
            ps.setArray(4, conn.createArrayOf("uuid", metricIds));
            ps.setArray(5, conn.createArrayOf("varchar", idempotencyKeys));
            ps.setArray(6, conn.createArrayOf("varchar", payloadHashes));
            ps.setArray(7, conn.createArrayOf("numeric", quantities));
            ps.setArray(8, conn.createArrayOf("numeric", measuredValues));
            ps.setArray(9, conn.createArrayOf("varchar", distinctKeys));
            ps.setArray(10, conn.createArrayOf("timestamptz", eventTimestamps));
            ps.setArray(11, conn.createArrayOf("timestamptz", ingestedAts));
            ps.setArray(12, conn.createArrayOf("text", propertiesJsons));
            ps.setArray(13, conn.createArrayOf("boolean", filterMatched));
            ps.setArray(14, conn.createArrayOf("boolean", lateEvents));
            ps.setArray(15, conn.createArrayOf("varchar", lateClassifications));
            ps.setArray(16, conn.createArrayOf("timestamptz", periodStarts));
            ps.setArray(17, conn.createArrayOf("timestamptz", periodEnds));
            ps.setArray(18, conn.createArrayOf("varchar", processingStatuses));
            return ps;
        }, (PreparedStatement ps) -> {
            Set<CustomerIdempotencyKey> inserted = new HashSet<>(n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    inserted.add(new CustomerIdempotencyKey(
                            rs.getObject(1, UUID.class),
                            rs.getString(2)
                    ));
                }
            }
            return inserted;
        });
    }

    /**
     * Fetches existing committed event digests in a single round-trip ONLY for conflicted keys
     * or expired-window replay verification.
     */
    Map<CustomerIdempotencyKey, ExistingEventDigest> findExistingByCustomerAndKeys(
            Collection<CustomerIdempotencyKey> keys
    ) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        int n = keys.size();
        UUID[] customerIds = new UUID[n];
        String[] idempotencyKeys = new String[n];
        int i = 0;
        for (CustomerIdempotencyKey key : keys) {
            customerIds[i] = key.customerId();
            idempotencyKeys[i] = key.idempotencyKey();
            i++;
        }

        String sql = """
                SELECT e.id, e.customer_id, e.idempotency_key, e.payload_hash, e.late_event, e.late_classification
                FROM usage_events e
                JOIN UNNEST(?::uuid[], ?::varchar[]) AS k(customer_id, idempotency_key)
                  ON e.customer_id = k.customer_id AND e.idempotency_key = k.idempotency_key
                """;

        return jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setArray(1, conn.createArrayOf("uuid", customerIds));
            ps.setArray(2, conn.createArrayOf("varchar", idempotencyKeys));
            return ps;
        }, (PreparedStatement ps) -> {
            Map<CustomerIdempotencyKey, ExistingEventDigest> map = new HashMap<>(n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID eventId = rs.getObject(1, UUID.class);
                    UUID custId = rs.getObject(2, UUID.class);
                    String idemKey = rs.getString(3);
                    map.put(new CustomerIdempotencyKey(custId, idemKey), new ExistingEventDigest(
                            eventId,
                            custId,
                            idemKey,
                            rs.getString(4),
                            rs.getBoolean(5),
                            rs.getString(6)
                    ));
                }
            }
            return map;
        });
    }

    /**
     * Atomically upserts deterministically sorted hourly rollup deltas inside the ingestion transaction.
     */
    void upsertRollupBuckets(List<RollupBucketAccumulator.BucketDelta> sortedDeltas, Instant updatedAt) {
        if (sortedDeltas.isEmpty()) {
            return;
        }
        int n = sortedDeltas.size();
        UUID[] customerIds = new UUID[n];
        UUID[] metricIds = new UUID[n];
        Timestamp[] bucketStarts = new Timestamp[n];
        Timestamp[] bucketEnds = new Timestamp[n];
        Long[] eventCounts = new Long[n];
        BigDecimal[] sumValues = new BigDecimal[n];
        BigDecimal[] maxValues = new BigDecimal[n];
        BigDecimal[] latestValues = new BigDecimal[n];
        Timestamp[] latestEventTimestamps = new Timestamp[n];
        Timestamp[] latestIngestedAts = new Timestamp[n];
        UUID[] latestEventIds = new UUID[n];
        Long[] lateEventCounts = new Long[n];
        Timestamp[] updatedAts = new Timestamp[n];
        Timestamp nowTs = Timestamp.from(updatedAt);

        for (int i = 0; i < n; i++) {
            RollupBucketAccumulator.BucketDelta d = sortedDeltas.get(i);
            customerIds[i] = d.customerId();
            metricIds[i] = d.metricId();
            bucketStarts[i] = Timestamp.from(d.bucketStart());
            bucketEnds[i] = Timestamp.from(d.bucketEnd());
            eventCounts[i] = d.eventCount();
            sumValues[i] = d.sumValue();
            maxValues[i] = d.maxValue();
            latestValues[i] = d.latestValue();
            latestEventTimestamps[i] = d.latestEventTimestamp() == null ? null : Timestamp.from(d.latestEventTimestamp());
            latestIngestedAts[i] = d.latestIngestedAt() == null ? null : Timestamp.from(d.latestIngestedAt());
            latestEventIds[i] = d.latestEventId();
            lateEventCounts[i] = d.lateEventCount();
            updatedAts[i] = nowTs;
        }

        String sql = """
                INSERT INTO usage_rollups (
                    customer_id, metric_id, bucket_start, bucket_end,
                    event_count, sum_value, max_value,
                    latest_value, latest_event_timestamp, latest_ingested_at, latest_event_id,
                    late_event_count, updated_at
                )
                SELECT
                    r.customer_id, r.metric_id, r.bucket_start, r.bucket_end,
                    r.event_count, r.sum_value, r.max_value,
                    r.latest_value, r.latest_event_timestamp, r.latest_ingested_at, r.latest_event_id,
                    r.late_event_count, r.updated_at
                FROM UNNEST(
                    ?::uuid[], ?::uuid[], ?::timestamptz[], ?::timestamptz[],
                    ?::bigint[], ?::numeric[], ?::numeric[],
                    ?::numeric[], ?::timestamptz[], ?::timestamptz[], ?::uuid[],
                    ?::bigint[], ?::timestamptz[]
                ) AS r(
                    customer_id, metric_id, bucket_start, bucket_end,
                    event_count, sum_value, max_value,
                    latest_value, latest_event_timestamp, latest_ingested_at, latest_event_id,
                    late_event_count, updated_at
                )
                ON CONFLICT (customer_id, metric_id, bucket_start) DO UPDATE SET
                    event_count = usage_rollups.event_count + EXCLUDED.event_count,
                    sum_value = usage_rollups.sum_value + EXCLUDED.sum_value,
                    max_value = CASE
                        WHEN usage_rollups.max_value IS NULL THEN EXCLUDED.max_value
                        WHEN EXCLUDED.max_value IS NULL THEN usage_rollups.max_value
                        ELSE GREATEST(usage_rollups.max_value, EXCLUDED.max_value)
                    END,
                    latest_value = CASE
                        WHEN EXCLUDED.latest_event_timestamp IS NULL THEN usage_rollups.latest_value
                        WHEN usage_rollups.latest_event_timestamp IS NULL THEN EXCLUDED.latest_value
                        WHEN (EXCLUDED.latest_event_timestamp, EXCLUDED.latest_ingested_at, CAST(EXCLUDED.latest_event_id AS text))
                           > (usage_rollups.latest_event_timestamp, usage_rollups.latest_ingested_at, CAST(usage_rollups.latest_event_id AS text))
                        THEN EXCLUDED.latest_value
                        ELSE usage_rollups.latest_value
                    END,
                    latest_event_timestamp = CASE
                        WHEN EXCLUDED.latest_event_timestamp IS NULL THEN usage_rollups.latest_event_timestamp
                        WHEN usage_rollups.latest_event_timestamp IS NULL THEN EXCLUDED.latest_event_timestamp
                        WHEN (EXCLUDED.latest_event_timestamp, EXCLUDED.latest_ingested_at, CAST(EXCLUDED.latest_event_id AS text))
                           > (usage_rollups.latest_event_timestamp, usage_rollups.latest_ingested_at, CAST(usage_rollups.latest_event_id AS text))
                        THEN EXCLUDED.latest_event_timestamp
                        ELSE usage_rollups.latest_event_timestamp
                    END,
                    latest_ingested_at = CASE
                        WHEN EXCLUDED.latest_event_timestamp IS NULL THEN usage_rollups.latest_ingested_at
                        WHEN usage_rollups.latest_event_timestamp IS NULL THEN EXCLUDED.latest_ingested_at
                        WHEN (EXCLUDED.latest_event_timestamp, EXCLUDED.latest_ingested_at, CAST(EXCLUDED.latest_event_id AS text))
                           > (usage_rollups.latest_event_timestamp, usage_rollups.latest_ingested_at, CAST(usage_rollups.latest_event_id AS text))
                        THEN EXCLUDED.latest_ingested_at
                        ELSE usage_rollups.latest_ingested_at
                    END,
                    latest_event_id = CASE
                        WHEN EXCLUDED.latest_event_timestamp IS NULL THEN usage_rollups.latest_event_id
                        WHEN usage_rollups.latest_event_timestamp IS NULL THEN EXCLUDED.latest_event_id
                        WHEN (EXCLUDED.latest_event_timestamp, EXCLUDED.latest_ingested_at, CAST(EXCLUDED.latest_event_id AS text))
                           > (usage_rollups.latest_event_timestamp, usage_rollups.latest_ingested_at, CAST(usage_rollups.latest_event_id AS text))
                        THEN EXCLUDED.latest_event_id
                        ELSE usage_rollups.latest_event_id
                    END,
                    late_event_count = usage_rollups.late_event_count + EXCLUDED.late_event_count,
                    updated_at = EXCLUDED.updated_at
                """;

        jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setArray(1, conn.createArrayOf("uuid", customerIds));
            ps.setArray(2, conn.createArrayOf("uuid", metricIds));
            ps.setArray(3, conn.createArrayOf("timestamptz", bucketStarts));
            ps.setArray(4, conn.createArrayOf("timestamptz", bucketEnds));
            ps.setArray(5, conn.createArrayOf("int8", eventCounts));
            ps.setArray(6, conn.createArrayOf("numeric", sumValues));
            ps.setArray(7, conn.createArrayOf("numeric", maxValues));
            ps.setArray(8, conn.createArrayOf("numeric", latestValues));
            ps.setArray(9, conn.createArrayOf("timestamptz", latestEventTimestamps));
            ps.setArray(10, conn.createArrayOf("timestamptz", latestIngestedAts));
            ps.setArray(11, conn.createArrayOf("uuid", latestEventIds));
            ps.setArray(12, conn.createArrayOf("int8", lateEventCounts));
            ps.setArray(13, conn.createArrayOf("timestamptz", updatedAts));
            return ps;
        }, (PreparedStatement ps) -> {
            ps.executeUpdate();
            return null;
        });
    }

    /**
     * Inserts late-event records cleanly in a single round-trip returning (usageEventId -> lateRecordId).
     */
    Map<UUID, UUID> insertLateEventRecords(List<LateEventInsert> lateEvents) {
        if (lateEvents.isEmpty()) {
            return Map.of();
        }
        int n = lateEvents.size();
        UUID[] usageEventIds = new UUID[n];
        UUID[] customerIds = new UUID[n];
        UUID[] subscriptionIds = new UUID[n];
        UUID[] metricIds = new UUID[n];
        Timestamp[] eventTimestamps = new Timestamp[n];
        Timestamp[] ingestedAts = new Timestamp[n];
        Timestamp[] closedPeriodStarts = new Timestamp[n];
        Timestamp[] closedPeriodEnds = new Timestamp[n];
        BigDecimal[] hoursLates = new BigDecimal[n];
        Timestamp[] createdAts = new Timestamp[n];

        for (int i = 0; i < n; i++) {
            LateEventInsert l = lateEvents.get(i);
            usageEventIds[i] = l.usageEventId();
            customerIds[i] = l.customerId();
            subscriptionIds[i] = l.subscriptionId();
            metricIds[i] = l.metricId();
            eventTimestamps[i] = Timestamp.from(l.eventTimestamp());
            ingestedAts[i] = Timestamp.from(l.ingestedAt());
            closedPeriodStarts[i] = l.closedPeriodStart() == null ? null : Timestamp.from(l.closedPeriodStart());
            closedPeriodEnds[i] = Timestamp.from(l.closedPeriodEnd());
            hoursLates[i] = l.hoursLate();
            createdAts[i] = Timestamp.from(l.createdAt());
        }

        String sql = """
                INSERT INTO usage_late_event_records (
                    usage_event_id, customer_id, subscription_id, metric_id,
                    event_timestamp, ingested_at, closed_period_start, closed_period_end,
                    hours_late, adjustment_status, created_at
                )
                SELECT
                    l.usage_event_id, l.customer_id, l.subscription_id, l.metric_id,
                    l.event_timestamp, l.ingested_at, l.closed_period_start, l.closed_period_end,
                    l.hours_late, 'PENDING_BILLING', l.created_at
                FROM UNNEST(
                    ?::uuid[], ?::uuid[], ?::uuid[], ?::uuid[],
                    ?::timestamptz[], ?::timestamptz[], ?::timestamptz[], ?::timestamptz[],
                    ?::numeric[], ?::timestamptz[]
                ) AS l(
                    usage_event_id, customer_id, subscription_id, metric_id,
                    event_timestamp, ingested_at, closed_period_start, closed_period_end,
                    hours_late, created_at
                )
                ON CONFLICT (usage_event_id) DO NOTHING
                RETURNING id, usage_event_id
                """;

        return jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setArray(1, conn.createArrayOf("uuid", usageEventIds));
            ps.setArray(2, conn.createArrayOf("uuid", customerIds));
            ps.setArray(3, conn.createArrayOf("uuid", subscriptionIds));
            ps.setArray(4, conn.createArrayOf("uuid", metricIds));
            ps.setArray(5, conn.createArrayOf("timestamptz", eventTimestamps));
            ps.setArray(6, conn.createArrayOf("timestamptz", ingestedAts));
            ps.setArray(7, conn.createArrayOf("timestamptz", closedPeriodStarts));
            ps.setArray(8, conn.createArrayOf("timestamptz", closedPeriodEnds));
            ps.setArray(9, conn.createArrayOf("numeric", hoursLates));
            ps.setArray(10, conn.createArrayOf("timestamptz", createdAts));
            return ps;
        }, (PreparedStatement ps) -> {
            Map<UUID, UUID> inserted = new HashMap<>(n);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    inserted.put(rs.getObject(2, UUID.class), rs.getObject(1, UUID.class));
                }
            }
            return inserted;
        });
    }

    void insertRejectedEvents(List<RejectedEventInsert> rejectedEvents) {
        if (rejectedEvents.isEmpty()) {
            return;
        }
        int n = rejectedEvents.size();
        UUID[] customerIds = new UUID[n];
        UUID[] metricIds = new UUID[n];
        String[] idempotencyKeys = new String[n];
        String[] codes = new String[n];
        String[] reasons = new String[n];
        Timestamp[] eventTimestamps = new Timestamp[n];
        String[] payloadHashes = new String[n];
        Timestamp[] rejectedAts = new Timestamp[n];

        for (int i = 0; i < n; i++) {
            RejectedEventInsert r = rejectedEvents.get(i);
            customerIds[i] = r.customerId();
            metricIds[i] = r.metricId();
            idempotencyKeys[i] = r.idempotencyKey();
            codes[i] = r.rejectionCode();
            reasons[i] = r.rejectionReason();
            eventTimestamps[i] = r.eventTimestamp() == null ? null : Timestamp.from(r.eventTimestamp());
            payloadHashes[i] = r.payloadHash();
            rejectedAts[i] = Timestamp.from(r.rejectedAt());
        }

        String sql = """
                INSERT INTO usage_rejected_events (
                    customer_id, metric_id, idempotency_key, rejection_code,
                    rejection_reason, event_timestamp, payload_hash, rejected_at
                )
                SELECT * FROM UNNEST(
                    ?::uuid[], ?::uuid[], ?::varchar[], ?::varchar[],
                    ?::varchar[], ?::timestamptz[], ?::varchar[], ?::timestamptz[]
                )
                """;

        jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setArray(1, conn.createArrayOf("uuid", customerIds));
            ps.setArray(2, conn.createArrayOf("uuid", metricIds));
            ps.setArray(3, conn.createArrayOf("varchar", idempotencyKeys));
            ps.setArray(4, conn.createArrayOf("varchar", codes));
            ps.setArray(5, conn.createArrayOf("varchar", reasons));
            ps.setArray(6, conn.createArrayOf("timestamptz", eventTimestamps));
            ps.setArray(7, conn.createArrayOf("varchar", payloadHashes));
            ps.setArray(8, conn.createArrayOf("timestamptz", rejectedAts));
            return ps;
        }, (PreparedStatement ps) -> {
            ps.executeUpdate();
            return null;
        });
    }

    /**
     * Atomically marks late-event records consumed by Billing.
     */
    int markLateEventsConsumed(Collection<UUID> lateRecordIds, Instant consumedAt) {
        if (lateRecordIds == null || lateRecordIds.isEmpty()) {
            return 0;
        }
        UUID[] ids = lateRecordIds.toArray(UUID[]::new);
        String sql = """
                UPDATE usage_late_event_records
                SET adjustment_status = 'CONSUMED_BY_BILLING',
                    consumed_at = ?
                WHERE id = ANY(?::uuid[])
                  AND adjustment_status = 'PENDING_BILLING'
                """;
        Integer count = jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setTimestamp(1, Timestamp.from(consumedAt));
            ps.setArray(2, conn.createArrayOf("uuid", ids));
            return ps;
        }, (PreparedStatement ps) -> ps.executeUpdate());
        return count == null ? 0 : count;
    }

    /**
     * Inserts a deduplicated threshold evaluation row atomically via ON CONFLICT DO NOTHING RETURNING.
     */
    UUID insertThresholdIfAbsent(
            UUID customerId,
            UUID subscriptionId,
            UUID metricId,
            String scopeKey,
            Instant windowStart,
            Instant windowEnd,
            int thresholdPercent,
            BigDecimal limitValue,
            BigDecimal currentValue,
            BigDecimal utilizationPercent,
            Instant triggeredAt
    ) {
        String sql = """
                INSERT INTO usage_threshold_evaluations (
                    customer_id, subscription_id, metric_id, scope_key,
                    window_start, window_end, threshold_percent,
                    limit_value, current_value, utilization_percent, triggered_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (customer_id, scope_key, window_start, window_end, threshold_percent) DO NOTHING
                RETURNING id
                """;
        List<UUID> ids = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                customerId,
                subscriptionId,
                metricId,
                scopeKey,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                thresholdPercent,
                limitValue,
                currentValue,
                utilizationPercent,
                Timestamp.from(triggeredAt)
        );
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /**
     * Counts exact distinct keys directly over indexed usage_events for UNIQUE_COUNT metrics.
     */
    long countDistinctKeysInWindow(UUID customerId, UUID metricId, Instant windowStart, Instant windowEnd) {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(DISTINCT distinct_key)
                FROM usage_events
                WHERE customer_id = ?
                  AND metric_id = ?
                  AND filter_matched = TRUE
                  AND distinct_key IS NOT NULL
                  AND event_timestamp >= ?
                  AND event_timestamp < ?
                """,
                Long.class,
                customerId,
                metricId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
        return count == null ? 0L : count;
    }

    /**
     * Fetches exact matched/late event counts in a window from indexed usage_events.
     */
    long[] countMatchedAndLateInWindow(UUID customerId, UUID metricId, Instant windowStart, Instant windowEnd) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    COUNT(*) FILTER (WHERE filter_matched = TRUE),
                    COUNT(*) FILTER (WHERE filter_matched = TRUE AND late_event = TRUE)
                FROM usage_events
                WHERE customer_id = ?
                  AND metric_id = ?
                  AND event_timestamp >= ?
                  AND event_timestamp < ?
                """,
                (rs, rowNum) -> new long[] { rs.getLong(1), rs.getLong(2) },
                customerId,
                metricId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd)
        );
    }

    record SliceAggregate(
            long matchedCount,
            long lateCount,
            BigDecimal sumValue,
            BigDecimal maxValue,
            BigDecimal latestValue,
            Instant latestEventTimestamp,
            Instant latestIngestedAt,
            UUID latestEventId
    ) {
    }

    /**
     * Computes exact bounded-memory SQL aggregates over a sub-hour or raw event slice without loading
     * UsageEventEntity rows or JSONB payloads into JVM heap (ADR-013).
     */
    SliceAggregate aggregateMatchedEventSlice(
            UUID customerId,
            UUID metricId,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        return jdbcTemplate.queryForObject(
                """
                WITH scoped AS (
                    SELECT id, measured_value, event_timestamp, ingested_at, late_event
                    FROM usage_events
                    WHERE customer_id = ?
                      AND metric_id = ?
                      AND filter_matched = TRUE
                      AND event_timestamp >= ?
                      AND event_timestamp < ?
                ),
                agg AS (
                    SELECT
                        COUNT(*) AS matched_count,
                        COUNT(*) FILTER (WHERE late_event = TRUE) AS late_count,
                        COALESCE(SUM(measured_value), 0) AS sum_value,
                        MAX(measured_value) AS max_value
                    FROM scoped
                ),
                latest AS (
                    SELECT measured_value, event_timestamp, ingested_at, id
                    FROM scoped
                    WHERE measured_value IS NOT NULL
                    ORDER BY event_timestamp DESC, ingested_at DESC, CAST(id AS text) DESC
                    LIMIT 1
                )
                SELECT
                    a.matched_count,
                    a.late_count,
                    a.sum_value,
                    a.max_value,
                    l.measured_value,
                    l.event_timestamp,
                    l.ingested_at,
                    l.id
                FROM agg a
                LEFT JOIN latest l ON TRUE
                """,
                (rs, rowNum) -> new SliceAggregate(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getBigDecimal(3) == null ? BigDecimal.ZERO : rs.getBigDecimal(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant(),
                        rs.getObject(8, UUID.class)
                ),
                customerId,
                metricId,
                Timestamp.from(fromInclusive),
                Timestamp.from(toExclusive)
        );
    }

    /**
     * Fetches traceable event IDs (bounded) for QA5 provenance without loading full JSONB payloads.
     */
    List<UUID> findTraceableEventIds(
            UUID customerId,
            UUID metricId,
            Instant windowStart,
            Instant windowEnd,
            int maxIds
    ) {
        return jdbcTemplate.query(
                """
                SELECT id
                FROM usage_events
                WHERE customer_id = ?
                  AND metric_id = ?
                  AND filter_matched = TRUE
                  AND event_timestamp >= ?
                  AND event_timestamp < ?
                ORDER BY event_timestamp ASC, ingested_at ASC, CAST(id AS text) ASC
                LIMIT ?
                """,
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                customerId,
                metricId,
                Timestamp.from(windowStart),
                Timestamp.from(windowEnd),
                maxIds
        );
    }

    /**
     * Reconciles hourly rollups for a customer/metric/hour-aligned range directly from authoritative usage_events
     * inside an ACID transaction, returning the reconciled bucket rows count, event count, and total sum.
     */
    record ReconcileStats(int bucketsReconciled, long eventsProcessed, BigDecimal reconciledSum, long matchedCount) {
    }

    ReconcileStats replaceRollupsFromAuthoritativeEvents(
            UUID customerId,
            UUID metricId,
            Instant fromBucket,
            Instant toBucket,
            Instant reconciledAt
    ) {
        jdbcTemplate.update(
                """
                DELETE FROM usage_rollups
                WHERE customer_id = ?
                  AND metric_id = ?
                  AND bucket_start >= ?
                  AND bucket_start < ?
                """,
                customerId,
                metricId,
                Timestamp.from(fromBucket),
                Timestamp.from(toBucket)
        );

        int bucketsInserted = jdbcTemplate.update(
                """
                INSERT INTO usage_rollups (
                    customer_id, metric_id, bucket_start, bucket_end,
                    event_count, sum_value, max_value,
                    latest_value, latest_event_timestamp, latest_ingested_at, latest_event_id,
                    late_event_count, last_reconciled_at, updated_at
                )
                WITH scoped AS (
                    SELECT
                        customer_id,
                        metric_id,
                        date_trunc('hour', event_timestamp AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' AS bucket_start,
                        measured_value,
                        event_timestamp,
                        ingested_at,
                        id,
                        late_event,
                        ROW_NUMBER() OVER (
                            PARTITION BY date_trunc('hour', event_timestamp AT TIME ZONE 'UTC')
                            ORDER BY event_timestamp DESC, ingested_at DESC, CAST(id AS text) DESC
                        ) AS rn
                    FROM usage_events
                    WHERE customer_id = ?
                      AND metric_id = ?
                      AND filter_matched = TRUE
                      AND event_timestamp >= ?
                      AND event_timestamp < ?
                ),
                agg AS (
                    SELECT
                        customer_id,
                        metric_id,
                        bucket_start,
                        COUNT(*) AS event_count,
                        COALESCE(SUM(measured_value), 0) AS sum_value,
                        MAX(measured_value) AS max_value,
                        COUNT(*) FILTER (WHERE late_event = TRUE) AS late_event_count
                    FROM scoped
                    GROUP BY customer_id, metric_id, bucket_start
                ),
                latest AS (
                    SELECT
                        bucket_start,
                        measured_value AS latest_value,
                        event_timestamp AS latest_event_timestamp,
                        ingested_at AS latest_ingested_at,
                        id AS latest_event_id
                    FROM scoped
                    WHERE rn = 1 AND measured_value IS NOT NULL
                )
                SELECT
                    a.customer_id,
                    a.metric_id,
                    a.bucket_start,
                    a.bucket_start + INTERVAL '1 hour',
                    a.event_count,
                    a.sum_value,
                    a.max_value,
                    l.latest_value,
                    l.latest_event_timestamp,
                    l.latest_ingested_at,
                    l.latest_event_id,
                    a.late_event_count,
                    ?,
                    ?
                FROM agg a
                LEFT JOIN latest l ON l.bucket_start = a.bucket_start
                ORDER BY a.bucket_start ASC
                """,
                customerId,
                metricId,
                Timestamp.from(fromBucket),
                Timestamp.from(toBucket),
                Timestamp.from(reconciledAt),
                Timestamp.from(reconciledAt)
        );

        int eventsMarked = jdbcTemplate.update(
                """
                UPDATE usage_events
                SET processing_status = 'RECONCILED'
                WHERE customer_id = ?
                  AND metric_id = ?
                  AND event_timestamp >= ?
                  AND event_timestamp < ?
                  AND processing_status <> 'RECONCILED'
                """,
                customerId,
                metricId,
                Timestamp.from(fromBucket),
                Timestamp.from(toBucket)
        );

        Object[] totals = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(sum_value), 0), COALESCE(SUM(event_count), 0)
                FROM usage_rollups
                WHERE customer_id = ?
                  AND metric_id = ?
                  AND bucket_start >= ?
                  AND bucket_start < ?
                """,
                (rs, rowNum) -> new Object[] { rs.getBigDecimal(1), rs.getLong(2) },
                customerId,
                metricId,
                Timestamp.from(fromBucket),
                Timestamp.from(toBucket)
        );

        BigDecimal reconciledSum = totals == null || totals[0] == null ? BigDecimal.ZERO : (BigDecimal) totals[0];
        long matchedCount = totals == null || totals[1] == null ? 0L : (Long) totals[1];
        return new ReconcileStats(bucketsInserted, Math.max(eventsMarked, matchedCount), reconciledSum, matchedCount);
    }

    /**
     * Crash-recovery helper: claims up to {@code maxEvents} rows stuck in {@code PENDING_ROLLUP}
     * using {@code FOR UPDATE SKIP LOCKED}, folds them into {@code usage_rollups}, and marks them {@code ROLLED_UP}.
     */
    int recoverPendingRollups(int maxEvents, Instant now) {
        record PendingRow(
                UUID id,
                UUID customerId,
                UUID metricId,
                Instant eventTimestamp,
                Instant ingestedAt,
                BigDecimal measuredValue,
                boolean filterMatched,
                boolean lateEvent
        ) {
        }

        List<PendingRow> rows = jdbcTemplate.query(
                """
                SELECT id, customer_id, metric_id, event_timestamp, ingested_at, measured_value, filter_matched, late_event
                FROM usage_events
                WHERE processing_status = 'PENDING_ROLLUP'
                ORDER BY ingested_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, rowNum) -> new PendingRow(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getTimestamp(4).toInstant(),
                        rs.getTimestamp(5).toInstant(),
                        rs.getBigDecimal(6),
                        rs.getBoolean(7),
                        rs.getBoolean(8)
                ),
                maxEvents
        );

        if (rows.isEmpty()) {
            return 0;
        }

        RollupBucketAccumulator accumulator = new RollupBucketAccumulator();
        UUID[] ids = new UUID[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            PendingRow r = rows.get(i);
            ids[i] = r.id();
            if (r.filterMatched()) {
                accumulator.add(
                        r.customerId(),
                        r.metricId(),
                        r.eventTimestamp(),
                        r.ingestedAt(),
                        r.id(),
                        r.measuredValue(),
                        r.lateEvent()
                );
            }
        }

        List<RollupBucketAccumulator.BucketDelta> deltas = accumulator.toSortedDeltas();
        if (!deltas.isEmpty()) {
            upsertRollupBuckets(deltas, now);
        }

        jdbcTemplate.execute(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE usage_events SET processing_status = 'ROLLED_UP' WHERE id = ANY(?::uuid[])"
            );
            Array arr = conn.createArrayOf("uuid", ids);
            ps.setArray(1, arr);
            return ps;
        }, (PreparedStatement ps) -> ps.executeUpdate());

        return rows.size();
    }
}
