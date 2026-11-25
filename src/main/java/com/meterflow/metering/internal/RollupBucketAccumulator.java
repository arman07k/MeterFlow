package com.meterflow.metering.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure hourly bucket pre-aggregator collapsing batch events into sorted deterministic
 * {@code (customer_id, metric_id, bucket_start)} deltas to eliminate row-lock deadlocks
 * and minimize PostgreSQL rollup write contention (ADR-013).
 */
final class RollupBucketAccumulator {

    record BucketKey(UUID customerId, UUID metricId, Instant bucketStart) implements Comparable<BucketKey> {

        private static final Comparator<BucketKey> ORDER = Comparator
                .comparing((BucketKey k) -> k.customerId().toString())
                .thenComparing(k -> k.metricId().toString())
                .thenComparing(BucketKey::bucketStart);

        @Override
        public int compareTo(BucketKey other) {
            return ORDER.compare(this, other);
        }
    }

    record BucketDelta(
            UUID customerId,
            UUID metricId,
            Instant bucketStart,
            Instant bucketEnd,
            long eventCount,
            BigDecimal sumValue,
            BigDecimal maxValue,
            BigDecimal latestValue,
            Instant latestEventTimestamp,
            Instant latestIngestedAt,
            UUID latestEventId,
            long lateEventCount
    ) {
    }

    private final Map<BucketKey, MutableBucket> buckets = new HashMap<>();

    static Instant truncateToHourUtc(Instant timestamp) {
        return timestamp.truncatedTo(ChronoUnit.HOURS);
    }

    static boolean isNewerLatest(
            Instant candidateEventTs,
            Instant candidateIngestedAt,
            UUID candidateEventId,
            Instant currentEventTs,
            Instant currentIngestedAt,
            UUID currentEventId
    ) {
        if (currentEventTs == null) {
            return true;
        }
        int cmpTs = candidateEventTs.compareTo(currentEventTs);
        if (cmpTs != 0) {
            return cmpTs > 0;
        }
        int cmpIng = candidateIngestedAt.compareTo(currentIngestedAt);
        if (cmpIng != 0) {
            return cmpIng > 0;
        }
        return candidateEventId.toString().compareTo(currentEventId.toString()) > 0;
    }

    void add(
            UUID customerId,
            UUID metricId,
            Instant eventTimestamp,
            Instant ingestedAt,
            UUID eventId,
            BigDecimal measuredValue,
            boolean lateEvent
    ) {
        Instant bucketStart = truncateToHourUtc(eventTimestamp);
        BucketKey key = new BucketKey(customerId, metricId, bucketStart);
        MutableBucket bucket = buckets.computeIfAbsent(key, k -> new MutableBucket());
        bucket.eventCount++;
        if (lateEvent) {
            bucket.lateEventCount++;
        }
        if (measuredValue != null) {
            bucket.sumValue = bucket.sumValue.add(measuredValue);
            if (bucket.maxValue == null || measuredValue.compareTo(bucket.maxValue) > 0) {
                bucket.maxValue = measuredValue;
            }
            if (isNewerLatest(
                    eventTimestamp,
                    ingestedAt,
                    eventId,
                    bucket.latestEventTimestamp,
                    bucket.latestIngestedAt,
                    bucket.latestEventId
            )) {
                bucket.latestValue = measuredValue;
                bucket.latestEventTimestamp = eventTimestamp;
                bucket.latestIngestedAt = ingestedAt;
                bucket.latestEventId = eventId;
            }
        }
    }

    List<BucketDelta> toSortedDeltas() {
        List<BucketKey> sortedKeys = new ArrayList<>(buckets.keySet());
        sortedKeys.sort(Comparator.naturalOrder());

        List<BucketDelta> result = new ArrayList<>(sortedKeys.size());
        for (BucketKey key : sortedKeys) {
            MutableBucket b = buckets.get(key);
            result.add(new BucketDelta(
                    key.customerId(),
                    key.metricId(),
                    key.bucketStart(),
                    key.bucketStart().plus(1, ChronoUnit.HOURS),
                    b.eventCount,
                    b.sumValue,
                    b.maxValue,
                    b.latestValue,
                    b.latestEventTimestamp,
                    b.latestIngestedAt,
                    b.latestEventId,
                    b.lateEventCount
            ));
        }
        return result;
    }

    private static final class MutableBucket {
        long eventCount;
        BigDecimal sumValue = BigDecimal.ZERO;
        BigDecimal maxValue;
        BigDecimal latestValue;
        Instant latestEventTimestamp;
        Instant latestIngestedAt;
        UUID latestEventId;
        long lateEventCount;
    }
}
