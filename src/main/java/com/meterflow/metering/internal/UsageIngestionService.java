package com.meterflow.metering.internal;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.InvalidCatalogConfigurationException;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.catalog.api.MetricAggregationType;
import com.meterflow.catalog.api.MetricCatalogFacade;
import com.meterflow.catalog.api.MetricEvaluationSupport;
import com.meterflow.catalog.api.MetricEventSample;
import com.meterflow.identity.api.CustomerStatus;
import com.meterflow.identity.api.CustomerSummary;
import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.metering.api.BatchIngestionResult;
import com.meterflow.metering.api.BatchLimitExceededException;
import com.meterflow.metering.api.EventIngestionItemResult;
import com.meterflow.metering.api.EventIngestionOutcome;
import com.meterflow.metering.api.EventProcessingStatus;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.metering.api.LateUsageEventRecordedEvent;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * High-throughput, partial-acceptance, database-idempotent usage event ingestion service (FR1, FR2, ADR-013).
 */
@Service
class UsageIngestionService implements UsageIngestionFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(UsageIngestionService.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 150;
    private static final int QUANTITY_SCALE = 6;

    private final IdentityQueryFacade identityQueryFacade;
    private final MetricCatalogFacade metricCatalogFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final MeteringBatchJdbcRepository batchJdbcRepository;
    private final MeteringCacheHelper cacheHelper;
    private final ApplicationEventPublisher eventPublisher;
    private final MeteringMetrics metrics;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    UsageIngestionService(
            IdentityQueryFacade identityQueryFacade,
            MetricCatalogFacade metricCatalogFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            MeteringBatchJdbcRepository batchJdbcRepository,
            MeteringCacheHelper cacheHelper,
            ApplicationEventPublisher eventPublisher,
            MeteringMetrics metrics,
            JsonMapper jsonMapper,
            Clock clock
    ) {
        this.identityQueryFacade = identityQueryFacade;
        this.metricCatalogFacade = metricCatalogFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.batchJdbcRepository = batchJdbcRepository;
        this.cacheHelper = cacheHelper;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Override
    @Transactional
    public EventIngestionItemResult ingestSingle(IngestUsageEventCommand command) {
        if (command == null) {
            throw new InvalidMeteringRequestException("Usage event payload must not be null");
        }
        BatchIngestionResult batchResult = ingestBatch(new IngestUsageBatchCommand(List.of(command)));
        return batchResult.results().getFirst();
    }

    @Override
    @Transactional
    public BatchIngestionResult ingestBatch(IngestUsageBatchCommand command) {
        if (command == null || command.events().isEmpty()) {
            throw new InvalidMeteringRequestException("Batch must contain between 1 and " + MAX_BATCH_SIZE + " events");
        }
        List<IngestUsageEventCommand> incoming = command.events();
        if (incoming.size() > MAX_BATCH_SIZE) {
            throw new BatchLimitExceededException(
                    "Batch size " + incoming.size() + " exceeds maximum allowed size of " + MAX_BATCH_SIZE
            );
        }

        long startNanos = System.nanoTime();
        metrics.recordBatchSize(incoming.size());
        Instant ingestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        // 1. Batched lookups (zero N+1 queries)
        Set<UUID> distinctCustomerIds = new HashSet<>();
        Set<UUID> distinctMetricIds = new HashSet<>();
        for (IngestUsageEventCommand e : incoming) {
            if (e != null) {
                if (e.customerId() != null) {
                    distinctCustomerIds.add(e.customerId());
                }
                if (e.metricId() != null) {
                    distinctMetricIds.add(e.metricId());
                }
            }
        }

        Map<UUID, CustomerSummary> customers = identityQueryFacade.findCustomersByIds(distinctCustomerIds);
        Map<UUID, BillableMetricDefinition> metricsById = metricCatalogFacade.findMetricsByIds(distinctMetricIds);
        Map<UUID, SubscriptionSummary> openSubscriptions = subscriptionQueryFacade
                .findOpenSubscriptionsByCustomerIds(distinctCustomerIds);

        EventIngestionItemResult[] itemResults = new EventIngestionItemResult[incoming.size()];
        List<MeteringBatchJdbcRepository.PreparedUsageEvent> candidatesToInsert = new ArrayList<>(incoming.size());
        Map<MeteringBatchJdbcRepository.CustomerIdempotencyKey, MeteringBatchJdbcRepository.PreparedUsageEvent> inBatchSeen =
                new HashMap<>(incoming.size());
        List<IntraBatchFollowUp> intraBatchFollowUps = new ArrayList<>();
        List<ExpiredReplayCheck> expiredReplayChecks = new ArrayList<>();
        List<MeteringBatchJdbcRepository.RejectedEventInsert> rejectedAuditRows = new ArrayList<>();

        // 2. Independent per-event validation & intra-batch deduplication
        for (int i = 0; i < incoming.size(); i++) {
            IngestUsageEventCommand raw = incoming.get(i);
            if (raw == null) {
                itemResults[i] = rejected(i, null, null, "NULL_EVENT", "Event entry must not be null", rejectedAuditRows, null, null, null, null, ingestedAt);
                continue;
            }
            if (raw.customerId() == null) {
                itemResults[i] = rejected(i, raw.eventId(), raw.idempotencyKey(), "MISSING_CUSTOMER", "customerId is required", rejectedAuditRows, null, raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }
            if (raw.metricId() == null) {
                itemResults[i] = rejected(i, raw.eventId(), raw.idempotencyKey(), "MISSING_METRIC", "metricId is required", rejectedAuditRows, raw.customerId(), null, raw.eventTimestamp(), null, ingestedAt);
                continue;
            }
            if (raw.eventTimestamp() == null) {
                itemResults[i] = rejected(i, raw.eventId(), raw.idempotencyKey(), "MISSING_TIMESTAMP", "eventTimestamp is required", rejectedAuditRows, raw.customerId(), raw.metricId(), null, null, ingestedAt);
                continue;
            }
            String idemKey = normalizeIdempotencyKey(raw.idempotencyKey());
            if (idemKey == null) {
                itemResults[i] = rejected(i, raw.eventId(), raw.idempotencyKey(), "INVALID_IDEMPOTENCY_KEY", "idempotencyKey must be non-blank and <= 150 characters", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }

            CustomerSummary customer = customers.get(raw.customerId());
            if (customer == null) {
                itemResults[i] = rejected(i, raw.eventId(), idemKey, "CUSTOMER_NOT_FOUND", "Customer does not exist", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }
            if (customer.status() != CustomerStatus.ACTIVE) {
                itemResults[i] = rejected(i, raw.eventId(), idemKey, "CUSTOMER_INACTIVE", "Customer account is not active", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }

            BillableMetricDefinition metric = metricsById.get(raw.metricId());
            if (metric == null) {
                itemResults[i] = rejected(i, raw.eventId(), idemKey, "METRIC_NOT_FOUND", "Billable metric does not exist", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }
            if (!metric.active()) {
                itemResults[i] = rejected(i, raw.eventId(), idemKey, "METRIC_INACTIVE", "Billable metric is disabled", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }
            if (metric.customerId() != null && !metric.customerId().equals(raw.customerId())) {
                itemResults[i] = rejected(i, raw.eventId(), idemKey, "METRIC_OWNERSHIP_MISMATCH", "Metric does not belong to customer", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                continue;
            }

            SubscriptionSummary openSub = openSubscriptions.get(raw.customerId());
            UUID resolvedSubscriptionId = raw.subscriptionId();
            if (resolvedSubscriptionId != null) {
                if (openSub != null && openSub.id().equals(resolvedSubscriptionId)) {
                    // matched open subscription
                } else {
                    SubscriptionSummary explicitSub = subscriptionQueryFacade
                            .findSubscriptionById(resolvedSubscriptionId)
                            .orElse(null);
                    if (explicitSub == null || !explicitSub.customerId().equals(raw.customerId())) {
                        itemResults[i] = rejected(i, raw.eventId(), idemKey, "INVALID_SUBSCRIPTION", "subscriptionId does not belong to customer", rejectedAuditRows, raw.customerId(), raw.metricId(), raw.eventTimestamp(), null, ingestedAt);
                        continue;
                    }
                    openSub = explicitSub;
                }
            } else if (openSub != null) {
                resolvedSubscriptionId = openSub.id();
            }

            Instant eventTimestamp = raw.eventTimestamp().truncatedTo(ChronoUnit.MICROS);
            UUID eventId = raw.eventId() != null ? raw.eventId() : UUID.randomUUID();

            BigDecimal quantity = raw.quantity();
            if (quantity != null && quantity.compareTo(BigDecimal.ZERO) < 0) {
                itemResults[i] = rejected(i, eventId, idemKey, "INVALID_QUANTITY", "quantity must not be negative", rejectedAuditRows, raw.customerId(), raw.metricId(), eventTimestamp, null, ingestedAt);
                continue;
            }

            Map<String, Object> effectiveProperties = buildEffectiveProperties(raw.properties(), metric, quantity);
            boolean filterMatched = MetricEvaluationSupport.matchesAllFilters(metric.filters(), effectiveProperties);

            BigDecimal measuredValue = null;
            String distinctKey = null;
            try {
                MetricEventSample sample = new MetricEventSample(eventId, eventTimestamp, ingestedAt, effectiveProperties);
                if (filterMatched) {
                    switch (metric.aggregationType()) {
                        case COUNT -> measuredValue = BigDecimal.ONE;
                        case SUM, MAXIMUM, LATEST_VALUE -> measuredValue = MetricEvaluationSupport
                                .extractRequiredNumericProperty(metric, sample)
                                .setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
                        case UNIQUE_COUNT -> distinctKey = MetricEvaluationSupport
                                .extractRequiredDistinctKey(metric, sample);
                    }
                } else if (metric.aggregationType() != MetricAggregationType.COUNT
                        && effectiveProperties.containsKey(metric.targetProperty())) {
                    // Reject malformed target property types even on unmatched events so corrupted payloads fail explicitly
                    if (metric.aggregationType() == MetricAggregationType.UNIQUE_COUNT) {
                        MetricEvaluationSupport.extractRequiredDistinctKey(metric, sample);
                    } else {
                        MetricEvaluationSupport.extractRequiredNumericProperty(metric, sample);
                    }
                }
            } catch (InvalidRatingContextException | InvalidCatalogConfigurationException ex) {
                itemResults[i] = rejected(i, eventId, idemKey, "INVALID_EVENT_PROPERTIES", ex.getMessage(), rejectedAuditRows, raw.customerId(), raw.metricId(), eventTimestamp, null, ingestedAt);
                continue;
            }

            BigDecimal normalizedQuantity;
            if (quantity != null) {
                normalizedQuantity = quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
            } else if (measuredValue != null && measuredValue.compareTo(BigDecimal.ZERO) >= 0) {
                normalizedQuantity = measuredValue;
            } else {
                normalizedQuantity = BigDecimal.ONE.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
            }

            String payloadHash = CanonicalPayloadHasher.computeHash(
                    raw.customerId(),
                    raw.metricId(),
                    resolvedSubscriptionId,
                    normalizedQuantity,
                    eventTimestamp,
                    raw.properties()
            );

            // Check temporal/late policy; if expired >72h, defer to DB idempotency replay check (FR1: idempotency before 72h expiry!)
            LateEventPolicyEvaluator.Evaluation temporal = LateEventPolicyEvaluator.evaluate(
                    eventTimestamp,
                    ingestedAt,
                    openSub
            );
            if (temporal.decision() == LateEventPolicyEvaluator.Decision.REJECT_FUTURE_TIMESTAMP) {
                itemResults[i] = rejected(i, eventId, idemKey, "FUTURE_TIMESTAMP", temporal.rejectionReason(), rejectedAuditRows, raw.customerId(), raw.metricId(), eventTimestamp, payloadHash, ingestedAt);
                continue;
            }
            if (temporal.decision() == LateEventPolicyEvaluator.Decision.REJECT_LATE_WINDOW_EXCEEDED) {
                expiredReplayChecks.add(new ExpiredReplayCheck(
                        i,
                        eventId,
                        new MeteringBatchJdbcRepository.CustomerIdempotencyKey(raw.customerId(), idemKey),
                        raw.metricId(),
                        eventTimestamp,
                        payloadHash,
                        temporal.rejectionReason()
                ));
                continue;
            }

            MeteringBatchJdbcRepository.CustomerIdempotencyKey dedupKey =
                    new MeteringBatchJdbcRepository.CustomerIdempotencyKey(raw.customerId(), idemKey);
            MeteringBatchJdbcRepository.PreparedUsageEvent priorInBatch = inBatchSeen.get(dedupKey);
            if (priorInBatch != null) {
                if (priorInBatch.payloadHash().equals(payloadHash)) {
                    // Defer resolution until after DB insert/conflict check so if the DB holds a different
                    // payload for this key, follow-ups also report CONFLICT rather than false DUPLICATE.
                    intraBatchFollowUps.add(new IntraBatchFollowUp(
                            i,
                            eventId,
                            priorInBatch,
                            raw.metricId(),
                            eventTimestamp,
                            payloadHash
                    ));
                } else {
                    itemResults[i] = conflict(
                            i, eventId, idemKey,
                            "Idempotency key reused with conflicting payload within batch",
                            rejectedAuditRows, raw.customerId(), raw.metricId(), eventTimestamp, payloadHash, ingestedAt
                    );
                }
                continue;
            }

            String propsJson = serializeProperties(raw.properties());
            boolean isLate = temporal.decision() == LateEventPolicyEvaluator.Decision.ACCEPT_LATE_WITHIN_72H;
            MeteringBatchJdbcRepository.PreparedUsageEvent prepared = new MeteringBatchJdbcRepository.PreparedUsageEvent(
                    i,
                    eventId,
                    raw.customerId(),
                    resolvedSubscriptionId,
                    raw.metricId(),
                    idemKey,
                    payloadHash,
                    normalizedQuantity,
                    measuredValue,
                    distinctKey,
                    eventTimestamp,
                    ingestedAt,
                    propsJson,
                    filterMatched,
                    isLate,
                    temporal.classification(),
                    temporal.closedPeriodStart(),
                    temporal.closedPeriodEnd(),
                    temporal.hoursLate(),
                    temporal.billingPeriodStart(),
                    temporal.billingPeriodEnd(),
                    EventProcessingStatus.ROLLED_UP
            );
            inBatchSeen.put(dedupKey, prepared);
            candidatesToInsert.add(prepared);
        }

        // 3. Resolve expired-window events against DB idempotency first (FR1 rule: historical replay => DUPLICATE)
        if (!expiredReplayChecks.isEmpty()) {
            List<MeteringBatchJdbcRepository.CustomerIdempotencyKey> expiredKeys = expiredReplayChecks.stream()
                    .map(ExpiredReplayCheck::key)
                    .toList();
            Map<MeteringBatchJdbcRepository.CustomerIdempotencyKey, MeteringBatchJdbcRepository.ExistingEventDigest> existingExpired =
                    batchJdbcRepository.findExistingByCustomerAndKeys(expiredKeys);
            for (ExpiredReplayCheck check : expiredReplayChecks) {
                MeteringBatchJdbcRepository.ExistingEventDigest existing = existingExpired.get(check.key());
                if (existing != null) {
                    if (existing.payloadHash().equals(check.payloadHash())) {
                        itemResults[check.batchIndex()] = new EventIngestionItemResult(
                                check.batchIndex(), existing.eventId(), check.key().idempotencyKey(),
                                EventIngestionOutcome.DUPLICATE, null, "Historical duplicate event acknowledged"
                        );
                    } else {
                        itemResults[check.batchIndex()] = conflict(
                                check.batchIndex(), check.eventId(), check.key().idempotencyKey(),
                                "Idempotency key already committed with a different payload",
                                rejectedAuditRows, check.key().customerId(), check.metricId(),
                                check.eventTimestamp(), check.payloadHash(), ingestedAt
                        );
                    }
                } else {
                    itemResults[check.batchIndex()] = rejected(
                            check.batchIndex(), check.eventId(), check.key().idempotencyKey(),
                            "LATE_WINDOW_EXCEEDED", check.rejectionReason(),
                            rejectedAuditRows, check.key().customerId(), check.metricId(),
                            check.eventTimestamp(), check.payloadHash(), ingestedAt
                    );
                }
            }
        }

        // 4. Single-round-trip PostgreSQL UNNEST insert with ON CONFLICT DO NOTHING RETURNING
        Set<MeteringBatchJdbcRepository.CustomerIdempotencyKey> insertedKeys = candidatesToInsert.isEmpty()
                ? Set.of()
                : batchJdbcRepository.insertEventsIgnoreConflicts(candidatesToInsert);

        List<MeteringBatchJdbcRepository.PreparedUsageEvent> newlyInserted = new ArrayList<>(insertedKeys.size());
        List<MeteringBatchJdbcRepository.PreparedUsageEvent> conflictedWithDb = new ArrayList<>();
        for (MeteringBatchJdbcRepository.PreparedUsageEvent candidate : candidatesToInsert) {
            MeteringBatchJdbcRepository.CustomerIdempotencyKey key =
                    new MeteringBatchJdbcRepository.CustomerIdempotencyKey(candidate.customerId(), candidate.idempotencyKey());
            if (insertedKeys.contains(key)) {
                newlyInserted.add(candidate);
                itemResults[candidate.batchIndex()] = new EventIngestionItemResult(
                        candidate.batchIndex(),
                        candidate.eventId(),
                        candidate.idempotencyKey(),
                        candidate.lateEvent() ? EventIngestionOutcome.ACCEPTED_LATE : EventIngestionOutcome.ACCEPTED,
                        null,
                        candidate.lateEvent() ? "Late event accepted within 72-hour window" : "Accepted"
                );
            } else {
                conflictedWithDb.add(candidate);
            }
        }

        // 5. Resolve DB conflicts ONLY for keys omitted from RETURNING (typically 0%-3%)
        if (!conflictedWithDb.isEmpty()) {
            List<MeteringBatchJdbcRepository.CustomerIdempotencyKey> conflictKeys = conflictedWithDb.stream()
                    .map(c -> new MeteringBatchJdbcRepository.CustomerIdempotencyKey(c.customerId(), c.idempotencyKey()))
                    .toList();
            Map<MeteringBatchJdbcRepository.CustomerIdempotencyKey, MeteringBatchJdbcRepository.ExistingEventDigest> existingMap =
                    batchJdbcRepository.findExistingByCustomerAndKeys(conflictKeys);
            for (MeteringBatchJdbcRepository.PreparedUsageEvent c : conflictedWithDb) {
                MeteringBatchJdbcRepository.CustomerIdempotencyKey key =
                        new MeteringBatchJdbcRepository.CustomerIdempotencyKey(c.customerId(), c.idempotencyKey());
                MeteringBatchJdbcRepository.ExistingEventDigest existing = existingMap.get(key);
                if (existing != null && existing.payloadHash().equals(c.payloadHash())) {
                    itemResults[c.batchIndex()] = new EventIngestionItemResult(
                            c.batchIndex(), existing.eventId(), c.idempotencyKey(),
                            EventIngestionOutcome.DUPLICATE, null, "Duplicate event acknowledged"
                    );
                } else {
                    itemResults[c.batchIndex()] = conflict(
                            c.batchIndex(), c.eventId(), c.idempotencyKey(),
                            "Idempotency key already used with a materially different payload",
                            rejectedAuditRows, c.customerId(), c.metricId(),
                            c.eventTimestamp(), c.payloadHash(), ingestedAt
                    );
                }
            }
        }

        // 5b. Resolve intra-batch identical follow-ups based on their leader's authoritative DB outcome
        for (IntraBatchFollowUp followUp : intraBatchFollowUps) {
            EventIngestionItemResult leaderResult = itemResults[followUp.leader().batchIndex()];
            if (leaderResult.outcome() == EventIngestionOutcome.CONFLICT) {
                itemResults[followUp.batchIndex()] = conflict(
                        followUp.batchIndex(),
                        followUp.eventId(),
                        followUp.leader().idempotencyKey(),
                        "Idempotency key already used with a materially different payload",
                        rejectedAuditRows,
                        followUp.leader().customerId(),
                        followUp.metricId(),
                        followUp.eventTimestamp(),
                        followUp.payloadHash(),
                        ingestedAt
                );
            } else {
                itemResults[followUp.batchIndex()] = new EventIngestionItemResult(
                        followUp.batchIndex(),
                        leaderResult.eventId(),
                        followUp.leader().idempotencyKey(),
                        EventIngestionOutcome.DUPLICATE,
                        null,
                        "Duplicate event within batch"
                );
            }
        }

        // 6. Transactionally upsert pre-aggregated hourly rollups for ONLY newly inserted matched rows
        if (!newlyInserted.isEmpty()) {
            long rollupStart = System.nanoTime();
            RollupBucketAccumulator accumulator = new RollupBucketAccumulator();
            List<MeteringBatchJdbcRepository.LateEventInsert> lateInserts = new ArrayList<>();
            Set<UUID> touchedCustomers = new HashSet<>();

            for (MeteringBatchJdbcRepository.PreparedUsageEvent e : newlyInserted) {
                touchedCustomers.add(e.customerId());
                long lagSec = Duration.between(e.eventTimestamp(), ingestedAt).getSeconds();
                if (lagSec >= 0) {
                    metrics.recordEventLagSeconds(lagSec);
                }
                if (e.filterMatched()) {
                    accumulator.add(
                            e.customerId(),
                            e.metricId(),
                            e.eventTimestamp(),
                            e.ingestedAt(),
                            e.eventId(),
                            e.measuredValue(),
                            e.lateEvent()
                    );
                }
                if (e.lateEvent()) {
                    lateInserts.add(new MeteringBatchJdbcRepository.LateEventInsert(
                            e.eventId(),
                            e.customerId(),
                            e.subscriptionId(),
                            e.metricId(),
                            e.eventTimestamp(),
                            e.ingestedAt(),
                            e.closedPeriodStart(),
                            e.closedPeriodEnd(),
                            e.hoursLate(),
                            ingestedAt
                    ));
                }
            }

            List<RollupBucketAccumulator.BucketDelta> sortedDeltas = accumulator.toSortedDeltas();
            if (!sortedDeltas.isEmpty()) {
                try {
                    batchJdbcRepository.upsertRollupBuckets(sortedDeltas, ingestedAt);
                } catch (RuntimeException ex) {
                    metrics.recordRollupFailure();
                    throw ex;
                }
            }
            metrics.recordRollupDuration(Duration.ofNanos(System.nanoTime() - rollupStart));

            // 7. Persist late-event adjustment records & publish outbox events for downstream Billing
            if (!lateInserts.isEmpty()) {
                Map<UUID, UUID> insertedLateIds = batchJdbcRepository.insertLateEventRecords(lateInserts);
                for (MeteringBatchJdbcRepository.LateEventInsert late : lateInserts) {
                    UUID lateRecordId = insertedLateIds.get(late.usageEventId());
                    if (lateRecordId != null) {
                        eventPublisher.publishEvent(new LateUsageEventRecordedEvent(
                                lateRecordId,
                                late.usageEventId(),
                                late.customerId(),
                                late.subscriptionId(),
                                late.metricId(),
                                late.eventTimestamp(),
                                late.ingestedAt(),
                                late.closedPeriodStart(),
                                late.closedPeriodEnd(),
                                late.hoursLate()
                        ));
                    }
                }
            }

            // 8. After-commit eviction of Caffeine liveEstimates for touched customers
            cacheHelper.evictLiveEstimatesAfterCommit(touchedCustomers);
        }

        // 9. Persist rejection/conflict audit rows (no raw sensitive payloads)
        if (!rejectedAuditRows.isEmpty()) {
            batchJdbcRepository.insertRejectedEvents(rejectedAuditRows);
        }

        // 10. Summarize outcomes and emit metrics
        int acceptedCount = 0;
        int lateAcceptedCount = 0;
        int duplicateCount = 0;
        int conflictCount = 0;
        int rejectedCount = 0;
        List<EventIngestionItemResult> finalResults = new ArrayList<>(incoming.size());
        for (EventIngestionItemResult r : itemResults) {
            finalResults.add(r);
            switch (r.outcome()) {
                case ACCEPTED -> acceptedCount++;
                case ACCEPTED_LATE -> lateAcceptedCount++;
                case DUPLICATE -> duplicateCount++;
                case CONFLICT -> conflictCount++;
                case REJECTED -> rejectedCount++;
            }
        }

        metrics.recordAccepted(acceptedCount);
        metrics.recordLateAccepted(lateAcceptedCount);
        metrics.recordDuplicates(duplicateCount);
        metrics.recordConflicts(conflictCount);
        metrics.recordIngestionDuration(Duration.ofNanos(System.nanoTime() - startNanos));

        if (conflictCount > 0 || rejectedCount > 0) {
            LOGGER.info(
                    "Usage batch processed: total={}, accepted={}, late={}, duplicate={}, conflict={}, rejected={}",
                    incoming.size(), acceptedCount, lateAcceptedCount, duplicateCount, conflictCount, rejectedCount
            );
        }

        return new BatchIngestionResult(
                incoming.size(),
                acceptedCount,
                lateAcceptedCount,
                duplicateCount,
                conflictCount,
                rejectedCount,
                ingestedAt,
                finalResults
        );
    }

    private EventIngestionItemResult rejected(
            int index,
            UUID eventId,
            String idempotencyKey,
            String errorCode,
            String message,
            List<MeteringBatchJdbcRepository.RejectedEventInsert> auditRows,
            UUID customerId,
            UUID metricId,
            Instant eventTimestamp,
            String payloadHash,
            Instant rejectedAt
    ) {
        metrics.recordRejected(errorCode);
        auditRows.add(new MeteringBatchJdbcRepository.RejectedEventInsert(
                customerId,
                metricId,
                truncateNullable(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH),
                errorCode,
                truncateNullable(message, 500),
                eventTimestamp == null ? null : eventTimestamp.truncatedTo(ChronoUnit.MICROS),
                payloadHash,
                rejectedAt
        ));
        return new EventIngestionItemResult(index, eventId, idempotencyKey, EventIngestionOutcome.REJECTED, errorCode, message);
    }

    private EventIngestionItemResult conflict(
            int index,
            UUID eventId,
            String idempotencyKey,
            String message,
            List<MeteringBatchJdbcRepository.RejectedEventInsert> auditRows,
            UUID customerId,
            UUID metricId,
            Instant eventTimestamp,
            String payloadHash,
            Instant rejectedAt
    ) {
        metrics.recordRejected("IDEMPOTENCY_KEY_CONFLICT");
        auditRows.add(new MeteringBatchJdbcRepository.RejectedEventInsert(
                customerId,
                metricId,
                truncateNullable(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH),
                "IDEMPOTENCY_KEY_CONFLICT",
                truncateNullable(message, 500),
                eventTimestamp == null ? null : eventTimestamp.truncatedTo(ChronoUnit.MICROS),
                payloadHash,
                rejectedAt
        ));
        return new EventIngestionItemResult(
                index, eventId, idempotencyKey, EventIngestionOutcome.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", message
        );
    }

    private static Map<String, Object> buildEffectiveProperties(
            Map<String, Object> rawProperties,
            BillableMetricDefinition metric,
            BigDecimal quantity
    ) {
        boolean needsQuantityFallback = metric.aggregationType() != MetricAggregationType.COUNT
                && metric.aggregationType() != MetricAggregationType.UNIQUE_COUNT
                && metric.targetProperty() != null
                && quantity != null
                && "quantity".equalsIgnoreCase(metric.targetProperty())
                && (rawProperties == null || !rawProperties.containsKey(metric.targetProperty()));
        if (!needsQuantityFallback) {
            return rawProperties == null ? Map.of() : rawProperties;
        }
        Map<String, Object> props = rawProperties == null ? new LinkedHashMap<>(2) : new LinkedHashMap<>(rawProperties);
        props.put(metric.targetProperty(), quantity);
        return props;
    }

    private String serializeProperties(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return "{}";
        }
        return jsonMapper.writeValueAsString(properties);
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH ? null : trimmed;
    }

    private static String truncateNullable(String text, int maxLen) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }

    private record ExpiredReplayCheck(
            int batchIndex,
            UUID eventId,
            MeteringBatchJdbcRepository.CustomerIdempotencyKey key,
            UUID metricId,
            Instant eventTimestamp,
            String payloadHash,
            String rejectionReason
    ) {
    }

    private record IntraBatchFollowUp(
            int batchIndex,
            UUID eventId,
            MeteringBatchJdbcRepository.PreparedUsageEvent leader,
            UUID metricId,
            Instant eventTimestamp,
            String payloadHash
    ) {
    }
}
