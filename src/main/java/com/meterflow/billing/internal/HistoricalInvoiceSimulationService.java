package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.CalculatedTaxLine;
import com.meterflow.billing.api.HistoricalInvoiceComparisonResult;
import com.meterflow.billing.api.HistoricalInvoiceSimulationFacade;
import com.meterflow.billing.api.HistoricalInvoiceSimulationRequest;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.InvoiceLineRecord;
import com.meterflow.billing.api.InvoiceLineType;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.SimulatedChargeLine;
import com.meterflow.billing.api.TaxCalculationContext;
import com.meterflow.billing.api.TaxCalculationStrategy;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.catalog.api.RatedChargeBreakdown;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.identity.api.AuthenticatedActor;
import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.identity.api.UnauthorizedCustomerAccessException;
import com.meterflow.metering.api.UsageAggregationResult;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageQueryRequest;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Strictly read-only historical invoice & plan-version comparison engine (FR10).
 * Reuses authoritative UsageQueryFacade, RatingEngineFacade, and TaxCalculationStrategy without mutating
 * any invoice, credit balance, payment, or plan version.
 */
@Service
@Transactional(readOnly = true)
class HistoricalInvoiceSimulationService implements HistoricalInvoiceSimulationFacade {

    private static final String SIMULATION_NOTICE =
            "Read-only historical simulation; no invoice, prepaid credit, payment, or plan version was modified.";
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final RatingEngineFacade ratingEngineFacade;
    private final UsageQueryFacade usageQueryFacade;
    private final TaxPolicyService taxPolicyService;
    private final TaxCalculationStrategy taxCalculationStrategy;
    private final CustomerAccessGuard accessGuard;

    HistoricalInvoiceSimulationService(
            InvoiceRepository invoiceRepository,
            InvoiceLineRepository invoiceLineRepository,
            SubscriptionQueryFacade subscriptionQueryFacade,
            PlanCatalogFacade planCatalogFacade,
            RatingEngineFacade ratingEngineFacade,
            UsageQueryFacade usageQueryFacade,
            TaxPolicyService taxPolicyService,
            TaxCalculationStrategy taxCalculationStrategy,
            CustomerAccessGuard accessGuard
    ) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.ratingEngineFacade = ratingEngineFacade;
        this.usageQueryFacade = usageQueryFacade;
        this.taxPolicyService = taxPolicyService;
        this.taxCalculationStrategy = taxCalculationStrategy;
        this.accessGuard = accessGuard;
    }

    @Override
    public HistoricalInvoiceComparisonResult simulateHistoricalInvoice(HistoricalInvoiceSimulationRequest request) {
        if (request == null || request.targetPlanVersionId() == null) {
            throw new InvalidBillingRequestException("targetPlanVersionId is required for historical simulation");
        }

        AuthenticatedActor actor = accessGuard.requireAuthenticatedActor();
        PlanVersionDetail targetPlan = planCatalogFacade.findPlanVersionById(request.targetPlanVersionId())
                .orElseThrow(() -> new BillingResourceNotFoundException(
                        "Target plan version not found: " + request.targetPlanVersionId()
                ));

        if (actor.role() == PlatformRole.CUSTOMER && targetPlan.status() != PlanVersionStatus.PUBLISHED) {
            throw new UnauthorizedCustomerAccessException(
                    "Customers may only simulate against PUBLISHED plan versions"
            );
        }

        UUID customerId;
        UUID subscriptionId;
        UUID originalInvoiceId = null;
        String originalInvoiceNumber = null;
        Instant periodStart;
        Instant periodEnd;
        SupportedCurrency currency;
        int seatQuantity;
        UUID originalPlanVersionId = null;
        String originalPlanLabel = "N/A";
        Money originalSubtotal;
        Money originalTaxTotal;
        Money originalTotal;
        Money originalCreditsApplied;
        Money originalPayableDue;
        List<InvoiceLineRecord> originalLines;
        List<String> dataWarnings = new ArrayList<>();

        if (request.originalInvoiceId() != null) {
            InvoiceEntity invoice = invoiceRepository.findById(request.originalInvoiceId())
                    .orElseThrow(() -> new BillingResourceNotFoundException(
                            "Original invoice not found: " + request.originalInvoiceId()
                    ));
            if (invoice.getStatus() == InvoiceStatus.VOID) {
                throw new InvalidBillingRequestException("Cannot simulate against a VOID invoice");
            }
            if (request.customerId() != null && !invoice.getCustomerId().equals(request.customerId())) {
                throw new UnauthorizedCustomerAccessException("Invoice does not belong to requested customer");
            }
            accessGuard.requireCustomerAccess(invoice.getCustomerId());

            customerId = invoice.getCustomerId();
            subscriptionId = invoice.getSubscriptionId();
            originalInvoiceId = invoice.getId();
            originalInvoiceNumber = invoice.getInvoiceNumber();
            periodStart = invoice.getPeriodStart();
            periodEnd = invoice.getPeriodEnd();
            currency = invoice.getCurrency();
            originalSubtotal = Money.of(invoice.getSubtotalAmount(), currency);
            originalTaxTotal = Money.of(invoice.getTaxAmount(), currency);
            originalTotal = Money.of(invoice.getTotalAmount(), currency);
            originalCreditsApplied = Money.of(invoice.getCreditsAppliedAmount(), currency);
            originalPayableDue = Money.of(invoice.getAmountDue(), currency);

            List<InvoiceLineEntity> lineEntities = invoiceLineRepository.findByInvoiceIdOrderByLineOrderAsc(invoice.getId());
            originalLines = lineEntities.stream().map(BillingMapper::toInvoiceLineRecord).toList();

            Integer inferredSeats = null;
            for (InvoiceLineRecord line : originalLines) {
                if (originalPlanVersionId == null && line.planVersionId() != null) {
                    originalPlanVersionId = line.planVersionId();
                }
                if (inferredSeats == null && line.seatQuantity() != null && line.seatQuantity() > 0) {
                    inferredSeats = line.seatQuantity();
                }
                if (line.lineType() == InvoiceLineType.PRORATION_ADJUSTMENT
                        || line.lineType() == InvoiceLineType.MANUAL_ADJUSTMENT
                        || line.lineType() == InvoiceLineType.LATE_USAGE_ADJUSTMENT) {
                    dataWarnings.add("Original invoice includes " + line.lineType().name()
                            + " (" + line.lineAmount().formatted() + "); simulated plan comparison rates base plan charges and usage only.");
                }
            }
            if (originalPlanVersionId != null) {
                originalPlanLabel = planCatalogFacade.findPlanVersionById(originalPlanVersionId)
                        .map(pv -> pv.planCode() + " v" + pv.versionNumber())
                        .orElse(originalPlanVersionId.toString());
            }
            if (request.seatQuantityOverride() != null && request.seatQuantityOverride() > 0) {
                seatQuantity = request.seatQuantityOverride();
            } else if (inferredSeats != null) {
                seatQuantity = inferredSeats;
            } else {
                seatQuantity = subscriptionQueryFacade.findSubscriptionById(subscriptionId)
                        .map(SubscriptionSummary::seatQuantity)
                        .orElse(1);
            }
        } else {
            if (request.customerId() == null || request.periodStart() == null || request.periodEnd() == null) {
                throw new InvalidBillingRequestException(
                        "Provide either originalInvoiceId or customerId + periodStart + periodEnd"
                );
            }
            if (!request.periodEnd().isAfter(request.periodStart())) {
                throw new InvalidBillingRequestException("periodEnd must be strictly after periodStart");
            }
            accessGuard.requireCustomerAccess(request.customerId());
            customerId = request.customerId();
            periodStart = request.periodStart();
            periodEnd = request.periodEnd();

            SubscriptionSummary subscription = request.subscriptionId() != null
                    ? subscriptionQueryFacade.findSubscriptionById(request.subscriptionId())
                            .orElseThrow(() -> new BillingResourceNotFoundException("Subscription not found"))
                    : subscriptionQueryFacade.findOpenSubscriptionByCustomerId(customerId).orElse(null);

            if (subscription != null && !subscription.customerId().equals(customerId)) {
                throw new UnauthorizedCustomerAccessException("Subscription does not belong to customer");
            }
            subscriptionId = subscription != null ? subscription.id() : null;
            currency = subscription != null ? subscription.currency() : targetPlan.currency();
            seatQuantity = request.seatQuantityOverride() != null && request.seatQuantityOverride() > 0
                    ? request.seatQuantityOverride()
                    : (subscription != null ? subscription.seatQuantity() : 1);
            originalPlanVersionId = subscription != null ? subscription.currentPlanVersionId() : null;
            if (originalPlanVersionId != null) {
                originalPlanLabel = planCatalogFacade.findPlanVersionById(originalPlanVersionId)
                        .map(pv -> pv.planCode() + " v" + pv.versionNumber())
                        .orElse(originalPlanVersionId.toString());
            }
            originalLines = List.of();
            originalSubtotal = Money.zero(currency);
            originalTaxTotal = Money.zero(currency);
            originalTotal = Money.zero(currency);
            originalCreditsApplied = Money.zero(currency);
            originalPayableDue = Money.zero(currency);
            dataWarnings.add("Period-only simulation without a baseline invoice; original totals default to 0.00.");
        }

        if (targetPlan.currency() != currency) {
            throw new CurrencyMismatchException(currency, targetPlan.currency());
        }

        // Query authoritative historical usage for every metric required by the alternative plan version.
        Map<UUID, UsageAggregationResult> usageByMetric = new LinkedHashMap<>();
        Set<UUID> requiredMetrics = new LinkedHashSet<>();
        for (ChargeDefinition charge : targetPlan.charges()) {
            if (charge.metricId() != null) {
                requiredMetrics.add(charge.metricId());
            }
        }
        for (UUID metricId : requiredMetrics) {
            UsageAggregationResult usage = usageQueryFacade.queryMetricUsage(
                    new UsageQueryRequest(customerId, metricId, periodStart, periodEnd)
            );
            usageByMetric.put(metricId, usage);
            if (usage.matchedEventCount() == 0) {
                dataWarnings.add("No historical usage events recorded for metric "
                        + usage.metricKey() + " (" + metricId + ") in [" + periodStart + ", " + periodEnd + "]; rated as 0.");
            }
        }

        List<MetricUsageInput> metricUsageInputs = usageByMetric.values().stream()
                .map(u -> new MetricUsageInput(u.metricId(), u.aggregatedQuantity()))
                .toList();

        PlanRatingResult ratingResult = ratingEngineFacade.rate(new RatingRequest(
                customerId,
                targetPlan.id(),
                currency,
                periodStart,
                periodEnd,
                seatQuantity,
                metricUsageInputs,
                null
        ));

        Map<UUID, ChargeDefinition> chargeDefs = new LinkedHashMap<>();
        for (ChargeDefinition charge : targetPlan.charges()) {
            chargeDefs.put(charge.id(), charge);
        }

        List<SimulatedChargeLine> simulatedLines = new ArrayList<>();
        Money simulatedSubtotal = Money.zero(currency);
        for (RatedChargeBreakdown breakdown : ratingResult.chargeBreakdowns()) {
            ChargeDefinition def = chargeDefs.get(breakdown.chargeId());
            String description = def != null && def.description() != null && !def.description().isBlank()
                    ? def.description()
                    : breakdown.chargeCode() + " (" + breakdown.chargeType() + ")";
            UsageAggregationResult usage = breakdown.metricId() != null ? usageByMetric.get(breakdown.metricId()) : null;
            long eventCount = usage != null ? usage.matchedEventCount() : 0L;
            String note = (breakdown.metricId() != null && eventCount == 0L)
                    ? "No usage events found for this metric in the selected historical window."
                    : null;
            Money lineAmount = breakdown.finalPersistedAmount().roundToPersisted();
            simulatedSubtotal = simulatedSubtotal.add(lineAmount);
            simulatedLines.add(new SimulatedChargeLine(
                    breakdown.chargeId(),
                    breakdown.chargeCode(),
                    breakdown.chargeType(),
                    description,
                    breakdown.metricId(),
                    breakdown.originalQuantity(),
                    breakdown.freeAllowanceQuantity(),
                    breakdown.billableQuantity(),
                    breakdown.effectiveUnitPrice(),
                    breakdown.appliedTiers(),
                    breakdown.chargeMinimumTrueUp(),
                    lineAmount,
                    eventCount,
                    note
            ));
        }

        Money planMinTrueUp = ratingResult.planMinimumTrueUp() != null
                ? ratingResult.planMinimumTrueUp().roundToPersisted()
                : Money.zero(currency);
        if (planMinTrueUp.isPositive()) {
            simulatedSubtotal = simulatedSubtotal.add(planMinTrueUp);
        }
        simulatedSubtotal = simulatedSubtotal.roundToPersisted();

        TaxPolicySummary taxPolicy = taxPolicyService.resolveApplicableTaxPolicy(customerId);
        List<CalculatedTaxLine> simulatedTaxLines = taxCalculationStrategy.calculateTaxLines(
                new TaxCalculationContext(customerId, currency, simulatedSubtotal, taxPolicy)
        );
        Money simulatedTaxTotal = Money.zero(currency);
        for (CalculatedTaxLine taxLine : simulatedTaxLines) {
            simulatedTaxTotal = simulatedTaxTotal.add(taxLine.taxAmount());
        }
        simulatedTaxTotal = simulatedTaxTotal.roundToPersisted();
        Money simulatedTotal = simulatedSubtotal.add(simulatedTaxTotal).roundToPersisted();

        // Keep credits non-mutating: reflect at most original credits applied up to simulated total.
        Money simulatedCreditsApplied = originalCreditsApplied.isGreaterThan(simulatedTotal)
                ? simulatedTotal
                : originalCreditsApplied;
        Money simulatedPayableDue = simulatedTotal.subtract(simulatedCreditsApplied).roundToPersisted();

        Money subtotalDelta = simulatedSubtotal.subtract(originalSubtotal).roundToPersisted();
        Money taxDelta = simulatedTaxTotal.subtract(originalTaxTotal).roundToPersisted();
        Money totalDelta = simulatedTotal.subtract(originalTotal).roundToPersisted();
        BigDecimal totalDeltaPercent = null;
        if (!originalTotal.isZero()) {
            totalDeltaPercent = totalDelta.amount()
                    .multiply(ONE_HUNDRED)
                    .divide(originalTotal.amount(), 2, RoundingMode.HALF_UP);
        }

        String simulatedPlanLabel = targetPlan.planCode() + " v" + targetPlan.versionNumber();
        return new HistoricalInvoiceComparisonResult(
                true,
                false,
                SIMULATION_NOTICE,
                customerId,
                subscriptionId,
                originalInvoiceId,
                originalInvoiceNumber,
                periodStart,
                periodEnd,
                currency,
                seatQuantity,
                originalPlanVersionId,
                originalPlanLabel,
                targetPlan.id(),
                simulatedPlanLabel,
                targetPlan.status(),
                originalSubtotal,
                originalTaxTotal,
                originalTotal,
                originalCreditsApplied,
                originalPayableDue,
                simulatedSubtotal,
                planMinTrueUp,
                simulatedTaxTotal,
                simulatedTotal,
                simulatedCreditsApplied,
                simulatedPayableDue,
                subtotalDelta,
                taxDelta,
                totalDelta,
                totalDeltaPercent,
                originalLines,
                simulatedLines,
                simulatedTaxLines,
                dataWarnings
        );
    }
}
