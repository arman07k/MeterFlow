package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.AllowanceOverride;
import com.meterflow.catalog.api.CatalogResourceNotFoundException;
import com.meterflow.catalog.api.ChargeCalculationContext;
import com.meterflow.catalog.api.ChargeCalculationOutcome;
import com.meterflow.catalog.api.ChargeCalculationStrategy;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.ChargeTypes;
import com.meterflow.catalog.api.FreeAllowanceDefinition;
import com.meterflow.catalog.api.InvalidRatingContextException;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.MinimumCommitmentDefinition;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.RatedChargeBreakdown;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.catalog.api.RecurringPlanQuote;
import com.meterflow.catalog.api.RecurringQuoteRequest;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Composite rating engine orchestrating free allowances (single-consumption ledger),
 * Open/Closed charge strategies, charge/plan minimum commitments, and ADR-010 rounding.
 *
 * <p>Reads authoritative plan definitions directly from PostgreSQL (never trusting Caffeine
 * cache state as financial authority).
 */
@Service
class PlanRatingComposite implements RatingEngineFacade {

    private final PlanVersionLoader planVersionLoader;
    private final ChargeStrategyRegistry strategyRegistry;

    PlanRatingComposite(PlanVersionLoader planVersionLoader, ChargeStrategyRegistry strategyRegistry) {
        this.planVersionLoader = planVersionLoader;
        this.strategyRegistry = strategyRegistry;
    }

    @Override
    @Transactional(readOnly = true)
    public PlanRatingResult rate(RatingRequest request) {
        validateRequest(request);
        PlanVersionDetail planVersion = planVersionLoader.loadAuthoritativePlanVersion(request.planVersionId())
                .orElseThrow(() -> new CatalogResourceNotFoundException("Plan version not found"));
        return ratePlanVersion(planVersion, request);
    }

    @Override
    @Transactional(readOnly = true)
    public RecurringPlanQuote rateRecurringCharges(RecurringQuoteRequest request) {
        if (request == null || request.planVersionId() == null) {
            throw new InvalidRatingContextException("RecurringQuoteRequest and planVersionId must not be null");
        }
        if (request.seatQuantity() != null && request.seatQuantity() < 0) {
            throw new InvalidRatingContextException("seatQuantity must not be negative");
        }
        PlanVersionDetail planVersion = planVersionLoader.loadAuthoritativePlanVersion(request.planVersionId())
                .orElseThrow(() -> new CatalogResourceNotFoundException("Plan version not found"));

        List<ChargeDefinition> recurringCharges = new ArrayList<>();
        List<String> excludedUsageCodes = new ArrayList<>();
        for (ChargeDefinition charge : planVersion.charges()) {
            if (isProratableRecurringCharge(charge)) {
                recurringCharges.add(charge);
            } else {
                excludedUsageCodes.add(charge.chargeCode());
            }
        }

        PlanVersionDetail recurringOnlyPlan = new PlanVersionDetail(
                planVersion.id(),
                planVersion.planId(),
                planVersion.planCode(),
                planVersion.planName(),
                planVersion.currency(),
                planVersion.versionNumber(),
                planVersion.status(),
                planVersion.effectiveFrom(),
                planVersion.publishedAt(),
                planVersion.clonedFromVersionId(),
                recurringCharges,
                planVersion.freeAllowances(),
                List.of(),
                planVersion.createdAt(),
                planVersion.updatedAt()
        );

        PlanRatingResult rated = ratePlanVersion(recurringOnlyPlan, new RatingRequest(
                null,
                planVersion.id(),
                planVersion.currency(),
                null,
                null,
                request.seatQuantity(),
                List.of(),
                null
        ));

        return new RecurringPlanQuote(
                planVersion.id(),
                planVersion.planCode(),
                planVersion.versionNumber(),
                planVersion.currency(),
                rated.calculatedUsageAmount(),
                rated.chargeBreakdowns(),
                excludedUsageCodes
        );
    }

    private static boolean isProratableRecurringCharge(ChargeDefinition charge) {
        if (ChargeTypes.FLAT_FEE.equalsIgnoreCase(charge.chargeType())) {
            return true;
        }
        return ChargeTypes.PER_SEAT.equalsIgnoreCase(charge.chargeType()) && charge.metricId() == null;
    }

    /**
     * Pure deterministic rating execution over an immutable {@link PlanVersionDetail}.
     */
    PlanRatingResult ratePlanVersion(PlanVersionDetail planVersion, RatingRequest request) {
        validateRequest(request);
        SupportedCurrency currency = planVersion.currency();
        if (request.currency() != null && request.currency() != currency) {
            throw new CurrencyMismatchException(currency, request.currency());
        }

        Map<UUID, BigDecimal> usageByMetric = buildUsageMap(request.metricUsages());
        AllowancePool allowancePool = AllowancePool.from(planVersion.freeAllowances(), request.allowanceOverrides());
        Map<UUID, Money> chargeCommitments = new HashMap<>();
        Money planCommitment = null;
        for (MinimumCommitmentDefinition commitment : planVersion.minimumCommitments()) {
            if (commitment.minimumAmount().currency() != currency) {
                throw new CurrencyMismatchException(currency, commitment.minimumAmount().currency());
            }
            if (commitment.isPlanLevel()) {
                planCommitment = commitment.minimumAmount();
            } else {
                chargeCommitments.put(commitment.planChargeId(), commitment.minimumAmount());
            }
        }

        List<ChargeDefinition> orderedCharges = planVersion.charges().stream()
                .sorted(Comparator.comparingInt(ChargeDefinition::displayOrder)
                        .thenComparing(ChargeDefinition::chargeCode))
                .toList();

        List<RatedChargeBreakdown> breakdowns = new ArrayList<>(orderedCharges.size());
        Money sumOfCharges = Money.zero(currency);

        for (ChargeDefinition charge : orderedCharges) {
            ChargeCalculationStrategy strategy = strategyRegistry.resolve(charge.chargeType());
            BigDecimal originalQty = resolveOriginalQuantity(charge, usageByMetric, request.seatQuantity());
            BigDecimal freeApplied = consumeAllowanceForCharge(charge, originalQty, allowancePool);
            BigDecimal billableQty = originalQty.subtract(freeApplied);

            ChargeCalculationContext ctx = new ChargeCalculationContext(
                    request.customerId(),
                    planVersion.id(),
                    currency,
                    charge,
                    originalQty,
                    freeApplied,
                    billableQty,
                    request.seatQuantity(),
                    request.periodStart(),
                    request.periodEnd()
            );

            ChargeCalculationOutcome outcome = strategy.calculate(ctx);
            if (outcome.rawAmount().currency() != currency) {
                throw new CurrencyMismatchException(currency, outcome.rawAmount().currency());
            }

            Money calculatedPersisted = outcome.rawAmount().roundToPersisted();
            Money chargeMin = chargeCommitments.get(charge.id());
            Money chargeTrueUp = Money.zero(currency).roundToPersisted();
            Money chargeFinal = calculatedPersisted;
            if (chargeMin != null && chargeMin.isGreaterThan(calculatedPersisted)) {
                chargeTrueUp = chargeMin.subtract(calculatedPersisted).roundToPersisted();
                chargeFinal = chargeMin.roundToPersisted();
            }

            sumOfCharges = sumOfCharges.add(chargeFinal);
            breakdowns.add(new RatedChargeBreakdown(
                    planVersion.id(),
                    charge.id(),
                    charge.chargeCode(),
                    charge.chargeType(),
                    charge.metricId(),
                    originalQty,
                    freeApplied,
                    billableQty,
                    outcome.effectiveUnitPrice(),
                    outcome.appliedTiers(),
                    calculatedPersisted,
                    chargeMin,
                    chargeTrueUp,
                    chargeFinal,
                    outcome.metadata()
            ));
        }

        Money calculatedUsageTotal = sumOfCharges.roundToPersisted();
        Money planTrueUp = Money.zero(currency).roundToPersisted();
        Money finalPersisted = calculatedUsageTotal;
        if (planCommitment != null && planCommitment.isGreaterThan(calculatedUsageTotal)) {
            planTrueUp = planCommitment.subtract(calculatedUsageTotal).roundToPersisted();
            finalPersisted = planCommitment.roundToPersisted();
        }
        Money finalPayable = finalPersisted.roundToPayable();

        return new PlanRatingResult(
                request.customerId(),
                planVersion.id(),
                planVersion.planCode(),
                planVersion.versionNumber(),
                currency,
                request.periodStart(),
                request.periodEnd(),
                breakdowns,
                calculatedUsageTotal,
                planCommitment,
                planTrueUp,
                finalPersisted,
                finalPayable
        );
    }

    private static BigDecimal resolveOriginalQuantity(
            ChargeDefinition charge,
            Map<UUID, BigDecimal> usageByMetric,
            Integer seatQuantity
    ) {
        if (ChargeTypes.FLAT_FEE.equalsIgnoreCase(charge.chargeType())) {
            return BigDecimal.ZERO;
        }
        if (charge.metricId() != null) {
            return usageByMetric.getOrDefault(charge.metricId(), BigDecimal.ZERO);
        }
        if (ChargeTypes.PER_SEAT.equalsIgnoreCase(charge.chargeType())) {
            if (seatQuantity == null) {
                throw new InvalidRatingContextException(
                        "seatQuantity is required to rate contracted PER_SEAT charge '" + charge.chargeCode() + "'"
                );
            }
            if (seatQuantity < 0) {
                throw new InvalidRatingContextException("seatQuantity must not be negative");
            }
            return BigDecimal.valueOf(seatQuantity);
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal consumeAllowanceForCharge(
            ChargeDefinition charge,
            BigDecimal originalQty,
            AllowancePool pool
    ) {
        if (originalQty.compareTo(BigDecimal.ZERO) <= 0
                || ChargeTypes.FLAT_FEE.equalsIgnoreCase(charge.chargeType())) {
            return BigDecimal.ZERO;
        }
        return pool.consume(charge.id(), charge.metricId(), originalQty);
    }

    private static Map<UUID, BigDecimal> buildUsageMap(List<MetricUsageInput> usages) {
        Map<UUID, BigDecimal> map = new LinkedHashMap<>();
        for (MetricUsageInput input : usages) {
            if (input == null || input.metricId() == null) {
                throw new InvalidRatingContextException("MetricUsageInput and metricId must not be null");
            }
            if (input.aggregatedQuantity() == null || input.aggregatedQuantity().compareTo(BigDecimal.ZERO) < 0) {
                throw new InvalidRatingContextException(
                        "Aggregated usage quantity must be non-null and >= 0 for metric " + input.metricId()
                );
            }
            if (map.containsKey(input.metricId())) {
                throw new InvalidRatingContextException(
                        "Duplicate metric usage input provided for metric " + input.metricId()
                );
            }
            map.put(input.metricId(), input.aggregatedQuantity());
        }
        return map;
    }

    private static void validateRequest(RatingRequest request) {
        if (request == null) {
            throw new InvalidRatingContextException("RatingRequest must not be null");
        }
        if (request.planVersionId() == null) {
            throw new InvalidRatingContextException("planVersionId must not be null");
        }
        if (request.seatQuantity() != null && request.seatQuantity() < 0) {
            throw new InvalidRatingContextException("seatQuantity must not be negative");
        }
        if (request.periodStart() != null && request.periodEnd() != null
                && !request.periodStart().isBefore(request.periodEnd())) {
            throw new InvalidRatingContextException("periodStart must be strictly before periodEnd");
        }
    }

    /**
     * Single-pass mutable allowance ledger preventing duplicate consumption across charges (ADR-011).
     */
    private static final class AllowancePool {

        private final List<AllowanceBucket> buckets;

        private AllowancePool(List<AllowanceBucket> buckets) {
            this.buckets = buckets;
        }

        static AllowancePool from(
                List<FreeAllowanceDefinition> configuredAllowances,
                List<AllowanceOverride> overrides
        ) {
            List<AllowanceBucket> list = new ArrayList<>();
            if (overrides != null) {
                for (AllowanceOverride ov : overrides) {
                    if (ov == null || ov.remainingFreeQuantity() == null
                            || ov.remainingFreeQuantity().compareTo(BigDecimal.ZERO) < 0) {
                        throw new InvalidRatingContextException("Allowance override quantity must be non-null and >= 0");
                    }
                    if (ov.metricId() == null && ov.targetChargeId() == null) {
                        throw new InvalidRatingContextException(
                                "Allowance override must target a metricId or targetChargeId"
                        );
                    }
                    list.add(new AllowanceBucket(ov.metricId(), ov.targetChargeId(), ov.remainingFreeQuantity()));
                }
            } else {
                for (FreeAllowanceDefinition def : configuredAllowances) {
                    list.add(new AllowanceBucket(def.metricId(), def.targetChargeId(), def.freeQuantity()));
                }
            }
            return new AllowancePool(list);
        }

        BigDecimal consume(UUID chargeId, UUID metricId, BigDecimal requested) {
            BigDecimal remainingToDeduct = requested;
            BigDecimal totalConsumed = BigDecimal.ZERO;

            // Pass 1: charge-specific allowances first
            for (AllowanceBucket bucket : buckets) {
                if (remainingToDeduct.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                if (chargeId != null && chargeId.equals(bucket.targetChargeId) && bucket.remaining.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal take = remainingToDeduct.min(bucket.remaining);
                    bucket.remaining = bucket.remaining.subtract(take);
                    remainingToDeduct = remainingToDeduct.subtract(take);
                    totalConsumed = totalConsumed.add(take);
                }
            }

            // Pass 2: shared metric-scoped allowances (targetChargeId == null)
            for (AllowanceBucket bucket : buckets) {
                if (remainingToDeduct.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                if (bucket.targetChargeId == null && metricId != null && metricId.equals(bucket.metricId)
                        && bucket.remaining.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal take = remainingToDeduct.min(bucket.remaining);
                    bucket.remaining = bucket.remaining.subtract(take);
                    remainingToDeduct = remainingToDeduct.subtract(take);
                    totalConsumed = totalConsumed.add(take);
                }
            }
            return totalConsumed;
        }
    }

    private static final class AllowanceBucket {
        final UUID metricId;
        final UUID targetChargeId;
        BigDecimal remaining;

        AllowanceBucket(UUID metricId, UUID targetChargeId, BigDecimal remaining) {
            this.metricId = metricId;
            this.targetChargeId = targetChargeId;
            this.remaining = remaining;
        }
    }
}
