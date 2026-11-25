package com.meterflow.metering.internal;

import com.meterflow.identity.api.IdentityQueryFacade;
import com.meterflow.identity.api.IdentityResourceNotFoundException;
import com.meterflow.metering.api.InvalidMeteringRequestException;
import com.meterflow.metering.api.ThresholdEvaluationCommand;
import com.meterflow.metering.api.ThresholdEvaluationResult;
import com.meterflow.metering.api.ThresholdTriggerRecord;
import com.meterflow.metering.api.UsageThresholdFacade;
import com.meterflow.metering.api.UsageThresholdReachedEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, deduplicated FR7 usage-threshold evaluator (50%, 80%, 100%) publishing
 * {@link UsageThresholdReachedEvent} via Spring Modulith's transactional outbox for future
 * Notifications and Webhooks modules without inventing budget values or delivering notifications (ADR-013).
 */
@Service
class UsageThresholdService implements UsageThresholdFacade {

    static final List<Integer> MANDATORY_THRESHOLDS = List.of(50, 80, 100);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final MeteringBatchJdbcRepository batchJdbcRepository;
    private final UsageThresholdEvaluationRepository thresholdRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final MeteringMetrics metrics;
    private final Clock clock;

    UsageThresholdService(
            MeteringBatchJdbcRepository batchJdbcRepository,
            UsageThresholdEvaluationRepository thresholdRepository,
            IdentityQueryFacade identityQueryFacade,
            ApplicationEventPublisher eventPublisher,
            MeteringMetrics metrics,
            Clock clock
    ) {
        this.batchJdbcRepository = batchJdbcRepository;
        this.thresholdRepository = thresholdRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.clock = clock;
    }

    static BigDecimal computeUtilizationPercent(BigDecimal currentValue, BigDecimal limitValue) {
        return currentValue.multiply(HUNDRED).divide(limitValue, 4, RoundingMode.HALF_UP);
    }

    static List<Integer> crossedThresholds(BigDecimal utilizationPercent) {
        List<Integer> crossed = new ArrayList<>(3);
        for (int pct : MANDATORY_THRESHOLDS) {
            if (utilizationPercent.compareTo(BigDecimal.valueOf(pct)) >= 0) {
                crossed.add(pct);
            }
        }
        return crossed;
    }

    @Override
    @Transactional
    public ThresholdEvaluationResult evaluateThresholds(ThresholdEvaluationCommand command) {
        if (command == null) {
            throw new InvalidMeteringRequestException("ThresholdEvaluationCommand must not be null");
        }
        if (command.customerId() == null) {
            throw new InvalidMeteringRequestException("customerId is required");
        }
        if (command.scopeKey() == null || command.scopeKey().isBlank() || command.scopeKey().trim().length() > 120) {
            throw new InvalidMeteringRequestException("scopeKey must be non-blank and <= 120 characters");
        }
        if (command.windowStart() == null || command.windowEnd() == null
                || !command.windowEnd().isAfter(command.windowStart())) {
            throw new InvalidMeteringRequestException("windowEnd must be strictly after windowStart");
        }
        if (command.limitValue() == null || command.limitValue().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidMeteringRequestException("limitValue must be strictly positive");
        }
        if (command.currentValue() == null || command.currentValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidMeteringRequestException("currentValue must not be negative");
        }
        if (identityQueryFacade.findCustomerById(command.customerId()).isEmpty()) {
            throw new IdentityResourceNotFoundException("Customer not found");
        }

        String scopeKey = command.scopeKey().trim();
        Instant windowStart = command.windowStart().truncatedTo(ChronoUnit.MICROS);
        Instant windowEnd = command.windowEnd().truncatedTo(ChronoUnit.MICROS);
        BigDecimal limit = command.limitValue().setScale(6, RoundingMode.HALF_UP);
        BigDecimal current = command.currentValue().setScale(6, RoundingMode.HALF_UP);
        BigDecimal utilizationPct = computeUtilizationPercent(current, limit);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);

        List<ThresholdTriggerRecord> newlyTriggered = new ArrayList<>();
        List<Integer> alreadyTriggered = new ArrayList<>();

        for (int thresholdPct : crossedThresholds(utilizationPct)) {
            UUID insertedId = batchJdbcRepository.insertThresholdIfAbsent(
                    command.customerId(),
                    command.subscriptionId(),
                    command.metricId(),
                    scopeKey,
                    windowStart,
                    windowEnd,
                    thresholdPct,
                    limit,
                    current,
                    utilizationPct,
                    now
            );
            if (insertedId != null) {
                ThresholdTriggerRecord record = new ThresholdTriggerRecord(
                        insertedId,
                        command.customerId(),
                        command.subscriptionId(),
                        command.metricId(),
                        scopeKey,
                        windowStart,
                        windowEnd,
                        thresholdPct,
                        limit,
                        current,
                        utilizationPct,
                        now
                );
                newlyTriggered.add(record);
                metrics.recordThresholdTriggered(thresholdPct);
                eventPublisher.publishEvent(new UsageThresholdReachedEvent(
                        insertedId,
                        command.customerId(),
                        command.subscriptionId(),
                        command.metricId(),
                        scopeKey,
                        windowStart,
                        windowEnd,
                        thresholdPct,
                        limit,
                        current,
                        utilizationPct,
                        now
                ));
            } else {
                alreadyTriggered.add(thresholdPct);
            }
        }

        return new ThresholdEvaluationResult(
                command.customerId(),
                scopeKey,
                current,
                limit,
                utilizationPct,
                newlyTriggered,
                alreadyTriggered
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThresholdTriggerRecord> listTriggeredThresholds(
            UUID customerId,
            Instant windowStart,
            Instant windowEnd
    ) {
        if (customerId == null || windowStart == null || windowEnd == null || !windowEnd.isAfter(windowStart)) {
            throw new InvalidMeteringRequestException("customerId and valid [windowStart, windowEnd) are required");
        }
        return thresholdRepository.findByCustomerAndWindow(customerId, windowStart, windowEnd)
                .stream()
                .map(t -> new ThresholdTriggerRecord(
                        t.getId(),
                        t.getCustomerId(),
                        t.getSubscriptionId(),
                        t.getMetricId(),
                        t.getScopeKey(),
                        t.getWindowStart(),
                        t.getWindowEnd(),
                        t.getThresholdPercent(),
                        t.getLimitValue(),
                        t.getCurrentValue(),
                        t.getUtilizationPercent(),
                        t.getTriggeredAt()
                ))
                .toList();
    }
}
