package com.meterflow.portal.internal;

import com.meterflow.catalog.api.MetricFilterDefinition;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.metering.api.BatchIngestionResult;
import com.meterflow.metering.api.EventIngestionItemResult;
import com.meterflow.metering.api.EventIngestionOutcome;
import com.meterflow.metering.api.IdempotencyConflictException;
import com.meterflow.metering.api.IngestUsageBatchCommand;
import com.meterflow.metering.api.IngestUsageEventCommand;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.metering.api.LateUsageEventRecord;
import com.meterflow.metering.api.LateUsageQueryFacade;
import com.meterflow.metering.api.LiveUsageEstimateRequest;
import com.meterflow.metering.api.LiveUsageEstimateResult;
import com.meterflow.metering.api.MeteringResourceNotFoundException;
import com.meterflow.metering.api.RollupReconciliationResult;
import com.meterflow.metering.api.ThresholdEvaluationCommand;
import com.meterflow.metering.api.ThresholdEvaluationResult;
import com.meterflow.metering.api.UsageAggregationResult;
import com.meterflow.metering.api.UsageEventRecord;
import com.meterflow.metering.api.UsageIngestionFacade;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageQueryRequest;
import com.meterflow.metering.api.UsageReconciliationFacade;
import com.meterflow.metering.api.UsageThresholdFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST controller exposing Metering ingestion, aggregation, live estimates, late-event queries,
 * reconciliation, and threshold evaluation strictly over {@code metering :: api} (FR1, FR2, FR7, ADR-013).
 */
@RestController
class MeteringController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UsageIngestionFacade ingestionFacade;
    private final UsageQueryFacade queryFacade;
    private final LateUsageQueryFacade lateUsageQueryFacade;
    private final UsageReconciliationFacade reconciliationFacade;
    private final UsageThresholdFacade thresholdFacade;
    private final CustomerAccessGuard customerAccessGuard;

    MeteringController(
            UsageIngestionFacade ingestionFacade,
            UsageQueryFacade queryFacade,
            LateUsageQueryFacade lateUsageQueryFacade,
            UsageReconciliationFacade reconciliationFacade,
            UsageThresholdFacade thresholdFacade,
            CustomerAccessGuard customerAccessGuard
    ) {
        this.ingestionFacade = ingestionFacade;
        this.queryFacade = queryFacade;
        this.lateUsageQueryFacade = lateUsageQueryFacade;
        this.reconciliationFacade = reconciliationFacade;
        this.thresholdFacade = thresholdFacade;
        this.customerAccessGuard = customerAccessGuard;
    }

    // ------------------------------------------------------------------------
    // Operator / Admin Endpoints (/api/v1/events/** & /api/v1/metering/**)
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/events")
    ResponseEntity<EventIngestionItemResult> ingestSingleEvent(@Valid @RequestBody SingleEventRequest request) {
        EventIngestionItemResult result = ingestionFacade.ingestSingle(toCommand(request, request.customerId()));
        return toSingleEventResponse(result);
    }

    @PostMapping("/api/v1/events/batch")
    ResponseEntity<BatchIngestionResult> ingestEventBatch(@Valid @RequestBody BatchEventRequest request) {
        List<IngestUsageEventCommand> commands = new ArrayList<>(request.events().size());
        for (SingleEventRequest e : request.events()) {
            commands.add(e == null ? null : toCommand(e, e.customerId()));
        }
        BatchIngestionResult result = ingestionFacade.ingestBatch(new IngestUsageBatchCommand(commands));
        return toBatchResponse(result);
    }

    @GetMapping("/api/v1/metering/events/{eventId}")
    UsageEventRecord getEventById(@PathVariable UUID eventId) {
        return queryFacade.findEventById(eventId)
                .orElseThrow(() -> new MeteringResourceNotFoundException("Usage event not found"));
    }

    @GetMapping("/api/v1/metering/customers/{customerId}/events")
    PageResponse<UsageEventRecord> listCustomerEventsAdmin(
            @PathVariable UUID customerId,
            @RequestParam(required = false) UUID metricId,
            @RequestParam(required = false) Instant windowStart,
            @RequestParam(required = false) Instant windowEnd,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(queryFacade.listCustomerEvents(customerId, metricId, windowStart, windowEnd, pageable));
    }

    @PostMapping("/api/v1/metering/usage/query")
    UsageAggregationResult queryMetricUsageAdmin(@Valid @RequestBody AdminUsageQueryRequest request) {
        return queryFacade.queryMetricUsage(new UsageQueryRequest(
                request.customerId(),
                request.metricId(),
                request.windowStart(),
                request.windowEnd(),
                request.additionalFilters(),
                Boolean.TRUE.equals(request.includeTraceableEventIds())
        ));
    }

    @PostMapping("/api/v1/metering/usage/live-estimate")
    LiveUsageEstimateResult estimateLiveUsageAdmin(@Valid @RequestBody AdminLiveEstimateRequest request) {
        return queryFacade.estimateLiveUsage(new LiveUsageEstimateRequest(request.customerId(), request.metricIds()));
    }

    @GetMapping("/api/v1/metering/late-events")
    PageResponse<LateUsageEventRecord> listPendingLateEvents(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(lateUsageQueryFacade.listPendingLateEvents(customerId, pageable));
    }

    @PostMapping("/api/v1/metering/rollups/reconcile")
    RollupReconciliationResult reconcileRollups(@Valid @RequestBody ReconcileRollupsRequest request) {
        return reconciliationFacade.reconcileRollups(
                request.customerId(),
                request.metricId(),
                request.windowStart(),
                request.windowEnd()
        );
    }

    @PostMapping("/api/v1/metering/rollups/recover")
    RecoverRollupsResponse recoverPendingRollups(@RequestParam(defaultValue = "1000") int maxEvents) {
        int recovered = reconciliationFacade.recoverPendingRollups(maxEvents);
        return new RecoverRollupsResponse(recovered);
    }

    @PostMapping("/api/v1/metering/thresholds/evaluate")
    ThresholdEvaluationResult evaluateThresholds(@Valid @RequestBody EvaluateThresholdsRequest request) {
        return thresholdFacade.evaluateThresholds(new ThresholdEvaluationCommand(
                request.customerId(),
                request.subscriptionId(),
                request.metricId(),
                request.scopeKey(),
                request.windowStart(),
                request.windowEnd(),
                request.limitValue(),
                request.currentValue()
        ));
    }

    // ------------------------------------------------------------------------
    // Customer-Scoped Endpoints (/api/v1/customer/{customerId}/metering/**)
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/customer/{customerId}/metering/events")
    ResponseEntity<EventIngestionItemResult> ingestCustomerSingleEvent(
            @PathVariable UUID customerId,
            @Valid @RequestBody SingleEventRequest request
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        if (request.customerId() != null && !request.customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Cross-customer usage event ingestion is denied");
        }
        EventIngestionItemResult result = ingestionFacade.ingestSingle(toCommand(request, customerId));
        return toSingleEventResponse(result);
    }

    @PostMapping("/api/v1/customer/{customerId}/metering/events/batch")
    ResponseEntity<BatchIngestionResult> ingestCustomerEventBatch(
            @PathVariable UUID customerId,
            @Valid @RequestBody BatchEventRequest request
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        List<IngestUsageEventCommand> commands = new ArrayList<>(request.events().size());
        for (SingleEventRequest e : request.events()) {
            if (e != null && e.customerId() != null && !e.customerId().equals(customerId)) {
                throw new UnauthorizedCustomerAccessException("Cross-customer usage event ingestion is denied");
            }
            commands.add(e == null ? null : toCommand(e, customerId));
        }
        BatchIngestionResult result = ingestionFacade.ingestBatch(new IngestUsageBatchCommand(commands));
        return toBatchResponse(result);
    }

    @GetMapping("/api/v1/customer/{customerId}/metering/events")
    PageResponse<UsageEventRecord> listCustomerEvents(
            @PathVariable UUID customerId,
            @RequestParam(required = false) UUID metricId,
            @RequestParam(required = false) Instant windowStart,
            @RequestParam(required = false) Instant windowEnd,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return PageResponse.from(queryFacade.listCustomerEvents(customerId, metricId, windowStart, windowEnd, pageable));
    }

    @GetMapping("/api/v1/customer/{customerId}/metering/events/{eventId}")
    UsageEventRecord getCustomerEventById(
            @PathVariable UUID customerId,
            @PathVariable UUID eventId
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        UsageEventRecord event = queryFacade.findEventById(eventId)
                .orElseThrow(() -> new MeteringResourceNotFoundException("Usage event not found"));
        if (!event.customerId().equals(customerId)) {
            throw new UnauthorizedCustomerAccessException("Usage event does not belong to customer");
        }
        return event;
    }

    @PostMapping("/api/v1/customer/{customerId}/metering/usage/query")
    UsageAggregationResult queryCustomerMetricUsage(
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerUsageQueryRequest request
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        return queryFacade.queryMetricUsage(new UsageQueryRequest(
                customerId,
                request.metricId(),
                request.windowStart(),
                request.windowEnd(),
                request.additionalFilters(),
                Boolean.TRUE.equals(request.includeTraceableEventIds())
        ));
    }

    @GetMapping("/api/v1/customer/{customerId}/metering/usage/live-estimate")
    LiveUsageEstimateResult getCustomerLiveEstimate(
            @PathVariable UUID customerId,
            @RequestParam(required = false) List<UUID> metricIds
    ) {
        customerAccessGuard.requireCustomerAccess(customerId);
        return queryFacade.estimateLiveUsage(new LiveUsageEstimateRequest(customerId, metricIds));
    }

    private static IngestUsageEventCommand toCommand(SingleEventRequest req, UUID resolvedCustomerId) {
        return new IngestUsageEventCommand(
                req.eventId(),
                resolvedCustomerId,
                req.metricId(),
                req.subscriptionId(),
                req.quantity(),
                req.eventTimestamp(),
                req.idempotencyKey(),
                req.properties()
        );
    }

    private static ResponseEntity<EventIngestionItemResult> toSingleEventResponse(EventIngestionItemResult result) {
        return switch (result.outcome()) {
            case ACCEPTED, ACCEPTED_LATE -> ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
            case DUPLICATE -> ResponseEntity.ok(result);
            case CONFLICT -> throw new IdempotencyConflictException(result.message());
            case REJECTED -> throw new InvalidMeteringRequestException(result.errorCode() + ": " + result.message());
        };
    }

    private static ResponseEntity<BatchIngestionResult> toBatchResponse(BatchIngestionResult result) {
        if (result.acceptedCount() + result.lateAcceptedCount() > 0) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
        }
        if (result.duplicateCount() == result.totalReceived()) {
            return ResponseEntity.ok(result);
        }
        if (result.conflictCount() == result.totalReceived()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(result);
        }
        if (result.rejectedCount() + result.conflictCount() == result.totalReceived()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(result);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    record SingleEventRequest(
            UUID eventId,
            UUID customerId,
            UUID metricId,
            UUID subscriptionId,
            BigDecimal quantity,
            Instant eventTimestamp,
            String idempotencyKey,
            Map<String, Object> properties
    ) {
    }

    record BatchEventRequest(
            @NotEmpty List<SingleEventRequest> events
    ) {
    }

    record AdminUsageQueryRequest(
            @NotNull UUID customerId,
            @NotNull UUID metricId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            List<MetricFilterDefinition> additionalFilters,
            Boolean includeTraceableEventIds
    ) {
    }

    record CustomerUsageQueryRequest(
            @NotNull UUID metricId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            List<MetricFilterDefinition> additionalFilters,
            Boolean includeTraceableEventIds
    ) {
    }

    record AdminLiveEstimateRequest(
            @NotNull UUID customerId,
            List<UUID> metricIds
    ) {
    }

    record ReconcileRollupsRequest(
            @NotNull UUID customerId,
            @NotNull UUID metricId,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd
    ) {
    }

    record RecoverRollupsResponse(int recoveredEvents) {
    }

    record EvaluateThresholdsRequest(
            @NotNull UUID customerId,
            UUID subscriptionId,
            UUID metricId,
            @NotBlank String scopeKey,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @NotNull BigDecimal limitValue,
            @NotNull BigDecimal currentValue
    ) {
    }
}
