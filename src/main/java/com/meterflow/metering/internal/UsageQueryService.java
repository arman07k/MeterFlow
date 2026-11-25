package com.meterflow.metering.internal;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.MetricEvaluationSupport;
import com.meterflow.catalog.api.MetricEventSample;
import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.catalog.api.MetricFilterOperator;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.metering.api.LiveMetricEstimate;
import com.meterflow.metering.api.LiveUsageEstimateRequest;
import com.meterflow.metering.api.LiveUsageEstimateResult;
import com.meterflow.metering.api.MeteringResourceNotFoundException;
import com.meterflow.metering.api.UsageAggregationResult;
import com.meterflow.metering.api.UsageDataSource;
import com.meterflow.metering.api.UsageEventRecord;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageQueryRequest;
import com.meterflow.shared.Money;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authoritative usage aggregation service & FR7 live-estimate foundation (ADR-013).
 *
 * <p>Authoritative {@link #queryMetricUsage(UsageQueryRequest)} always reads committed PostgreSQL
 * rollups/events and never consults Caffeine {@code liveEstimates}.
 */
@Service
@Transactional(readOnly = true)
class UsageQueryService implements UsageQueryFacade {

    private static final int MAX_TRACEABLE_EVENT_IDS = 1_000;

    private final UsageEventRepository eventRepository;
    private final UsageRollupRepository rollupRepository;
    private final MeteringBatchJdbcRepository batchJdbcRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final MetricCatalogFacade metricCatalogFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final RatingEngineFacade ratingEngineFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final MeteringCacheHelper cacheHelper;
    private final MeteringMetrics metrics;
    private final Clock clock;

    UsageQueryService(
            UsageEventRepository eventRepository,
            UsageRollupRepository rollupRepository,
            MeteringBatchJdbcRepository batchJdbcRepository,
            IdentityQueryFacade identityQueryFacade,
            MetricCatalogFacade metricCatalogFacade,
            PlanCatalogFacade planCatalogFacade,
            RatingEngineFacade ratingEngineFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            MeteringCacheHelper cacheHelper,
            MeteringMetrics metrics,
            Clock clock
    ) {
        this.eventRepository = eventRepository;
        this.rollupRepository = rollupRepository;
        this.batchJdbcRepository = batchJdbcRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.metricCatalogFacade = metricCatalogFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.ratingEngineFacade = ratingEngineFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.cacheHelper = cacheHelper;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public UsageAggregationResult queryMetricUsage(UsageQueryRequest request) {
        if (request == null || request.customerId() == null || request.metricId() == null) {
            throw new InvalidMeteringRequestException("customerId and metricId are required");
        }
        if (request.windowStart() == null || request.windowEnd() == null
                || !request.windowEnd().isAfter(request.windowStart())) {
            throw new InvalidMeteringRequestException("windowEnd must be strictly after windowStart");
        }

        long startNanos = System.nanoTime();
        Instant windowStart = request.windowStart().truncatedTo(ChronoUnit.MICROS);
        Instant windowEnd = request.windowEnd().truncatedTo(ChronoUnit.MICROS);

        if (identityQueryFacade.findCustomerById(request.customerId()).isEmpty()) {
            throw new IdentityResourceNotFoundException("Customer not found");
        }

        BillableMetricDefinition metric = metricCatalogFacade.findMetricById(request.metricId())
                .orElseThrow(() -> new MeteringResourceNotFoundException("Billable metric not found"));
        if (metric.customerId() != null && !metric.customerId().equals(request.customerId())) {
            throw new MeteringResourceNotFoundException("Billable metric not accessible to customer");
        }

        validateAdditionalFilters(request.additionalFilters());

        List<MetricFilterDefinition> allFilters = new ArrayList<>(metric.filters());
        allFilters.addAll(request.additionalFilters());

        Instant computedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UsageAggregationResult result;

        if (!request.additionalFilters().isEmpty()) {
            result = aggregateFromRawEventsWithFilters(
                    request.customerId(), metric, windowStart, windowEnd, allFilters,
                    request.includeTraceableEventIds(), computedAt
            );
        } else if (metric.aggregationType() == MetricAggregationType.UNIQUE_COUNT) {
            long distinctCount = batchJdbcRepository.countDistinctKeysInWindow(
                    request.customerId(), metric.id(), windowStart, windowEnd
            );
            long[] counts = batchJdbcRepository.countMatchedAndLateInWindow(
                    request.customerId(), metric.id(), windowStart, windowEnd
            );
            List<UUID> traceable = request.includeTraceableEventIds()
                    ? batchJdbcRepository.findTraceableEventIds(
                            request.customerId(), metric.id(), windowStart, windowEnd, MAX_TRACEABLE_EVENT_IDS)
                    : List.of();
            result = new UsageAggregationResult(
                    request.customerId(),
                    metric.id(),
                    metric.metricKey(),
                    metric.aggregationType(),
                    windowStart,
                    windowEnd,
                    BigDecimal.valueOf(distinctCount),
                    counts[0],
                    counts[1],
                    allFilters,
                    UsageDataSource.RAW_EVENTS,
                    computedAt,
                    0L,
                    traceable
            );
        } else {
            result = aggregateFromRollupsAndEdges(
                    request.customerId(), metric, windowStart, windowEnd, allFilters,
                    request.includeTraceableEventIds(), computedAt
            );
        }

        metrics.recordAggregationDuration(Duration.ofNanos(System.nanoTime() - startNanos));
        return result;
    }

    @Override
    public LiveUsageEstimateResult estimateLiveUsage(LiveUsageEstimateRequest request) {
        if (request == null || request.customerId() == null) {
            throw new InvalidMeteringRequestException("customerId is required");
        }
        long startNanos = System.nanoTime();
        try {
            if (identityQueryFacade.findCustomerById(request.customerId()).isEmpty()) {
                throw new IdentityResourceNotFoundException("Customer not found");
            }

            boolean useDefaultCache = request.metricIds().isEmpty();
            if (useDefaultCache) {
                Optional<LiveUsageEstimateResult> cached = cacheHelper.getLiveEstimate(request.customerId());
                if (cached.isPresent()) {
                    LiveUsageEstimateResult hit = cached.get();
                    return new LiveUsageEstimateResult(
                            hit.customerId(),
                            hit.subscriptionId(),
                            hit.planVersionId(),
                            hit.windowStart(),
                            hit.windowEnd(),
                            hit.metricEstimates(),
                            hit.estimatedTotalAmount(),
                            true,
                            hit.computedAt(),
                            MeteringCacheHelper.MAX_FRESHNESS_SECONDS
                    );
                }
            }

            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Optional<SubscriptionSummary> openSubOpt = subscriptionQueryFacade
                    .findOpenSubscriptionByCustomerId(request.customerId());

            UUID subscriptionId = null;
            UUID planVersionId = null;
            Instant windowStart;
            Instant windowEnd;
            PlanVersionDetail publishedPlan = null;

            if (openSubOpt.isPresent()) {
                SubscriptionSummary sub = openSubOpt.get();
                subscriptionId = sub.id();
                planVersionId = sub.currentPlanVersionId();
                if (sub.currentPeriodStart() != null && sub.currentPeriodEnd() != null) {
                    windowStart = sub.currentPeriodStart();
                    windowEnd = sub.currentPeriodEnd();
                } else if (sub.trialStartAt() != null && sub.trialEndAt() != null) {
                    windowStart = sub.trialStartAt();
                    windowEnd = sub.trialEndAt();
                } else {
                    windowStart = now.minus(Duration.ofDays(30));
                    windowEnd = now;
                }
                publishedPlan = planCatalogFacade.findPlanVersionById(planVersionId)
                        .filter(pv -> pv.status() == PlanVersionStatus.PUBLISHED)
                        .orElse(null);
            } else {
                windowStart = now.minus(Duration.ofHours(24));
                windowEnd = now;
            }

            Set<UUID> targetMetricIds = new LinkedHashSet<>();
            if (!request.metricIds().isEmpty()) {
                targetMetricIds.addAll(request.metricIds());
            } else if (publishedPlan != null) {
                for (ChargeDefinition charge : publishedPlan.charges()) {
                    if (charge.metricId() != null) {
                        targetMetricIds.add(charge.metricId());
                    }
                }
            }

            List<LiveMetricEstimate> metricEstimates = new ArrayList<>(targetMetricIds.size());
            List<MetricUsageInput> ratingInputs = new ArrayList<>(targetMetricIds.size());
            for (UUID metricId : targetMetricIds) {
                UsageAggregationResult agg = queryMetricUsage(
                        new UsageQueryRequest(request.customerId(), metricId, windowStart, windowEnd)
                );
                metricEstimates.add(new LiveMetricEstimate(
                        agg.metricId(),
                        agg.metricKey(),
                        agg.aggregationType(),
                        agg.aggregatedQuantity(),
                        agg.matchedEventCount(),
                        agg.lateEventCount()
                ));
                ratingInputs.add(new MetricUsageInput(agg.metricId(), agg.aggregatedQuantity()));
            }

            Money estimatedTotal = null;
            if (openSubOpt.isPresent() && publishedPlan != null) {
                SubscriptionSummary sub = openSubOpt.get();
                PlanRatingResult rated = ratingEngineFacade.rate(new RatingRequest(
                        request.customerId(),
                        publishedPlan.id(),
                        sub.currency(),
                        windowStart,
                        windowEnd,
                        sub.seatQuantity(),
                        ratingInputs,
                        null
                ));
                estimatedTotal = rated.finalPayableAmount();
            }

            LiveUsageEstimateResult fresh = new LiveUsageEstimateResult(
                    request.customerId(),
                    subscriptionId,
                    planVersionId,
                    windowStart,
                    windowEnd,
                    metricEstimates,
                    estimatedTotal,
                    false,
                    now,
                    MeteringCacheHelper.MAX_FRESHNESS_SECONDS
            );

            if (useDefaultCache) {
                cacheHelper.putLiveEstimate(request.customerId(), fresh);
            }
            return fresh;
        } finally {
            metrics.recordQueryDuration(Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    @Override
    public Page<UsageEventRecord> listCustomerEvents(
            UUID customerId,
            UUID metricId,
            Instant windowStart,
            Instant windowEnd,
            Pageable pageable
    ) {
        if (customerId == null) {
            throw new InvalidMeteringRequestException("customerId is required");
        }
        Instant effectiveStart = windowStart != null ? windowStart : Instant.EPOCH;
        Instant effectiveEnd = windowEnd != null ? windowEnd : Instant.parse("2100-01-01T00:00:00Z");
        Page<UsageEventEntity> page = (metricId == null)
                ? eventRepository.findByCustomerAndWindow(customerId, effectiveStart, effectiveEnd, pageable)
                : eventRepository.findByCustomerMetricAndWindow(customerId, metricId, effectiveStart, effectiveEnd, pageable);
        return page.map(UsageQueryService::toEventRecord);
    }

    @Override
    public Optional<UsageEventRecord> findEventById(UUID eventId) {
        if (eventId == null) {
            return Optional.empty();
        }
        return eventRepository.findById(eventId).map(UsageQueryService::toEventRecord);
    }

    private UsageAggregationResult aggregateFromRollupsAndEdges(
            UUID customerId,
            BillableMetricDefinition metric,
            Instant windowStart,
            Instant windowEnd,
            List<MetricFilterDefinition> appliedFilters,
            boolean includeTraceableEventIds,
            Instant computedAt
    ) {
        Instant startTrunc = windowStart.truncatedTo(ChronoUnit.HOURS);
        Instant firstFullHour = windowStart.equals(startTrunc) ? startTrunc : startTrunc.plus(1, ChronoUnit.HOURS);
        Instant lastFullHour = windowEnd.truncatedTo(ChronoUnit.HOURS);

        long matchedCount = 0L;
        long lateCount = 0L;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = null;
        BigDecimal latestValue = null;
        Instant latestEventTs = null;
        Instant latestIngestedAt = null;
        UUID latestEventId = null;

        UsageDataSource dataSource;
        if (firstFullHour.isBefore(lastFullHour)) {
            boolean hasLeadingEdge = windowStart.isBefore(firstFullHour);
            boolean hasTrailingEdge = lastFullHour.isBefore(windowEnd);
            dataSource = (hasLeadingEdge || hasTrailingEdge) ? UsageDataSource.ROLLUP_AND_EDGES : UsageDataSource.ROLLUP;

            List<UsageRollupEntity> rollups = rollupRepository.findBucketsInRange(
                    customerId, metric.id(), firstFullHour, lastFullHour
            );
            for (UsageRollupEntity r : rollups) {
                matchedCount += r.getEventCount();
                lateCount += r.getLateEventCount();
                sum = sum.add(r.getSumValue());
                if (r.getMaxValue() != null && (max == null || r.getMaxValue().compareTo(max) > 0)) {
                    max = r.getMaxValue();
                }
                if (r.getLatestEventTimestamp() != null && RollupBucketAccumulator.isNewerLatest(
                        r.getLatestEventTimestamp(), r.getLatestIngestedAt(), r.getLatestEventId(),
                        latestEventTs, latestIngestedAt, latestEventId
                )) {
                    latestValue = r.getLatestValue();
                    latestEventTs = r.getLatestEventTimestamp();
                    latestIngestedAt = r.getLatestIngestedAt();
                    latestEventId = r.getLatestEventId();
                }
            }

            List<MeteringBatchJdbcRepository.SliceAggregate> edgeSlices = new ArrayList<>(2);
            if (hasLeadingEdge) {
                edgeSlices.add(batchJdbcRepository.aggregateMatchedEventSlice(
                        customerId, metric.id(), windowStart, firstFullHour));
            }
            if (hasTrailingEdge) {
                edgeSlices.add(batchJdbcRepository.aggregateMatchedEventSlice(
                        customerId, metric.id(), lastFullHour, windowEnd));
            }
            for (MeteringBatchJdbcRepository.SliceAggregate slice : edgeSlices) {
                matchedCount += slice.matchedCount();
                lateCount += slice.lateCount();
                sum = sum.add(slice.sumValue());
                if (slice.maxValue() != null && (max == null || slice.maxValue().compareTo(max) > 0)) {
                    max = slice.maxValue();
                }
                if (slice.latestEventTimestamp() != null && RollupBucketAccumulator.isNewerLatest(
                        slice.latestEventTimestamp(), slice.latestIngestedAt(), slice.latestEventId(),
                        latestEventTs, latestIngestedAt, latestEventId
                )) {
                    latestValue = slice.latestValue();
                    latestEventTs = slice.latestEventTimestamp();
                    latestIngestedAt = slice.latestIngestedAt();
                    latestEventId = slice.latestEventId();
                }
            }
        } else {
            dataSource = UsageDataSource.RAW_EVENTS;
            MeteringBatchJdbcRepository.SliceAggregate slice = batchJdbcRepository.aggregateMatchedEventSlice(
                    customerId, metric.id(), windowStart, windowEnd
            );
            matchedCount = slice.matchedCount();
            lateCount = slice.lateCount();
            sum = slice.sumValue();
            max = slice.maxValue();
            latestValue = slice.latestValue();
        }

        BigDecimal aggregated = switch (metric.aggregationType()) {
            case COUNT -> BigDecimal.valueOf(matchedCount);
            case SUM -> sum;
            case MAXIMUM -> max == null ? BigDecimal.ZERO : max;
            case LATEST_VALUE -> latestValue == null ? BigDecimal.ZERO : latestValue;
            case UNIQUE_COUNT -> BigDecimal.ZERO;
        };

        List<UUID> traceable = includeTraceableEventIds
                ? batchJdbcRepository.findTraceableEventIds(
                        customerId, metric.id(), windowStart, windowEnd, MAX_TRACEABLE_EVENT_IDS)
                : List.of();

        return new UsageAggregationResult(
                customerId,
                metric.id(),
                metric.metricKey(),
                metric.aggregationType(),
                windowStart,
                windowEnd,
                aggregated,
                matchedCount,
                lateCount,
                appliedFilters,
                dataSource,
                computedAt,
                0L,
                traceable
        );
    }

    private UsageAggregationResult aggregateFromRawEventsWithFilters(
            UUID customerId,
            BillableMetricDefinition metric,
            Instant windowStart,
            Instant windowEnd,
            List<MetricFilterDefinition> combinedFilters,
            boolean includeTraceableEventIds,
            Instant computedAt
    ) {
        long matchedCount = 0L;
        long lateCount = 0L;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal max = null;
        BigDecimal latestValue = null;
        Instant latestEventTs = null;
        Instant latestIngestedAt = null;
        UUID latestEventId = null;
        Set<String> distinctValues = metric.aggregationType() == MetricAggregationType.UNIQUE_COUNT
                ? new LinkedHashSet<>()
                : Set.of();
        List<UUID> traceableIds = includeTraceableEventIds ? new ArrayList<>() : List.of();

        int pageNumber = 0;
        Page<UsageEventEntity> page;
        do {
            page = eventRepository.findPageByCustomerMetricAndWindow(
                    customerId, metric.id(), windowStart, windowEnd, PageRequest.of(pageNumber++, 1_000)
            );
            for (UsageEventEntity e : page.getContent()) {
                Map<String, Object> effectiveProps = new LinkedHashMap<>(e.getProperties());
                if (metric.aggregationType() != MetricAggregationType.COUNT
                        && metric.aggregationType() != MetricAggregationType.UNIQUE_COUNT
                        && metric.targetProperty() != null
                        && !effectiveProps.containsKey(metric.targetProperty())
                        && "quantity".equalsIgnoreCase(metric.targetProperty())) {
                    effectiveProps.put(metric.targetProperty(), e.getQuantity());
                }
                if (!MetricEvaluationSupport.matchesAllFilters(combinedFilters, effectiveProps)) {
                    continue;
                }
                matchedCount++;
                if (e.isLateEvent()) {
                    lateCount++;
                }
                if (includeTraceableEventIds && traceableIds.size() < MAX_TRACEABLE_EVENT_IDS) {
                    traceableIds.add(e.getId());
                }
                MetricEventSample sample = new MetricEventSample(
                        e.getId(), e.getEventTimestamp(), e.getIngestedAt(), effectiveProps
                );
                switch (metric.aggregationType()) {
                    case COUNT -> {
                        // counted above
                    }
                    case SUM -> sum = sum.add(MetricEvaluationSupport.extractRequiredNumericProperty(metric, sample));
                    case MAXIMUM -> {
                        BigDecimal val = MetricEvaluationSupport.extractRequiredNumericProperty(metric, sample);
                        if (max == null || val.compareTo(max) > 0) {
                            max = val;
                        }
                    }
                    case UNIQUE_COUNT -> distinctValues.add(MetricEvaluationSupport.extractRequiredDistinctKey(metric, sample));
                    case LATEST_VALUE -> {
                        BigDecimal val = MetricEvaluationSupport.extractRequiredNumericProperty(metric, sample);
                        if (RollupBucketAccumulator.isNewerLatest(
                                e.getEventTimestamp(), e.getIngestedAt(), e.getId(),
                                latestEventTs, latestIngestedAt, latestEventId
                        )) {
                            latestValue = val;
                            latestEventTs = e.getEventTimestamp();
                            latestIngestedAt = e.getIngestedAt();
                            latestEventId = e.getId();
                        }
                    }
                }
            }
        } while (page.hasNext());

        BigDecimal aggregated = switch (metric.aggregationType()) {
            case COUNT -> BigDecimal.valueOf(matchedCount);
            case SUM -> sum;
            case MAXIMUM -> max == null ? BigDecimal.ZERO : max;
            case UNIQUE_COUNT -> BigDecimal.valueOf(distinctValues.size());
            case LATEST_VALUE -> latestValue == null ? BigDecimal.ZERO : latestValue;
        };

        return new UsageAggregationResult(
                customerId,
                metric.id(),
                metric.metricKey(),
                metric.aggregationType(),
                windowStart,
                windowEnd,
                aggregated,
                matchedCount,
                lateCount,
                combinedFilters,
                UsageDataSource.RAW_EVENTS,
                computedAt,
                0L,
                traceableIds
        );
    }

    private static void validateAdditionalFilters(List<MetricFilterDefinition> filters) {
        for (MetricFilterDefinition f : filters) {
            if (f == null || f.propertyKey() == null || f.propertyKey().isBlank() || f.operator() == null) {
                throw new InvalidMeteringRequestException("Additional filter requires non-blank propertyKey and operator");
            }
            if (f.operator() != MetricFilterOperator.EXISTS && f.values().isEmpty()) {
                throw new InvalidMeteringRequestException(
                        "Filter operator " + f.operator() + " requires at least one comparison value"
                );
            }
        }
    }

    static UsageEventRecord toEventRecord(UsageEventEntity entity) {
        return new UsageEventRecord(
                entity.getId(),
                entity.getCustomerId(),
                entity.getSubscriptionId(),
                entity.getMetricId(),
                entity.getIdempotencyKey(),
                entity.getQuantity(),
                entity.getMeasuredValue(),
                entity.getDistinctKey(),
                entity.getEventTimestamp(),
                entity.getIngestedAt(),
                entity.getProperties(),
                entity.isFilterMatched(),
                entity.isLateEvent(),
                entity.getLateClassification(),
                entity.getBillingPeriodStart(),
                entity.getBillingPeriodEnd(),
                entity.getProcessingStatus()
        );
    }
}
