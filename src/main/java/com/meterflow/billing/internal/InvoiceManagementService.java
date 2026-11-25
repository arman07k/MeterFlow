package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingAdjustmentStatus;
import com.meterflow.billing.api.BillingAdjustmentType;
import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.CalculatedTaxLine;
import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.InvalidInvoiceTransitionException;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceFinalizedEvent;
import com.meterflow.billing.api.InvoiceLineType;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.billing.api.TaxCalculationContext;
import com.meterflow.billing.api.TaxCalculationStrategy;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.billing.api.VoidInvoiceCommand;
import com.meterflow.catalog.api.AppliedTierBreakdown;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.RatedChargeBreakdown;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.metering.api.UsageAggregationResult;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageQueryRequest;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.ProrationRecord;
import com.meterflow.subscriptions.api.SubscriptionHistory;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class InvoiceManagementService implements InvoiceManagementFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(InvoiceManagementService.class);

    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceTaxLineRepository invoiceTaxLineRepository;
    private final BillingAdjustmentRepository adjustmentRepository;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final RatingEngineFacade ratingEngineFacade;
    private final UsageQueryFacade usageQueryFacade;
    private final TaxPolicyService taxPolicyService;
    private final TaxCalculationStrategy taxCalculationStrategy;
    private final PrepaidCreditService prepaidCreditService;
    private final ApplicationEventPublisher eventPublisher;
    private final BillingMetrics metrics;
    private final int defaultGracePeriodHours;
    private final int defaultPaymentTermsDays;

    InvoiceManagementService(
            InvoiceRepository invoiceRepository,
            InvoiceLineRepository invoiceLineRepository,
            InvoiceTaxLineRepository invoiceTaxLineRepository,
            BillingAdjustmentRepository adjustmentRepository,
            SubscriptionQueryFacade subscriptionQueryFacade,
            PlanCatalogFacade planCatalogFacade,
            RatingEngineFacade ratingEngineFacade,
            UsageQueryFacade usageQueryFacade,
            TaxPolicyService taxPolicyService,
            TaxCalculationStrategy taxCalculationStrategy,
            PrepaidCreditService prepaidCreditService,
            ApplicationEventPublisher eventPublisher,
            BillingMetrics metrics,
            @Value("${meterflow.billing.default-grace-period-hours:72}") int defaultGracePeriodHours,
            @Value("${meterflow.billing.default-payment-terms-days:14}") int defaultPaymentTermsDays
    ) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.invoiceTaxLineRepository = invoiceTaxLineRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.ratingEngineFacade = ratingEngineFacade;
        this.usageQueryFacade = usageQueryFacade;
        this.taxPolicyService = taxPolicyService;
        this.taxCalculationStrategy = taxCalculationStrategy;
        this.prepaidCreditService = prepaidCreditService;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.defaultGracePeriodHours = Math.max(0, defaultGracePeriodHours);
        this.defaultPaymentTermsDays = Math.max(0, defaultPaymentTermsDays);
    }

    @Override
    @Transactional
    public InvoiceDetail generateOrRefreshDraft(GenerateDraftInvoiceCommand command) {
        long startNanos = System.nanoTime();
        if (command == null || command.subscriptionId() == null) {
            throw new InvalidBillingRequestException("subscriptionId must not be null");
        }

        invoiceRepository.lockSubscriptionForBilling(command.subscriptionId());

        SubscriptionSummary subscription = subscriptionQueryFacade.findSubscriptionById(command.subscriptionId())
                .orElseThrow(() -> new BillingResourceNotFoundException(
                        "Subscription not found: " + command.subscriptionId()));

        Instant periodStart = command.periodStart() != null
                ? command.periodStart().truncatedTo(ChronoUnit.MICROS)
                : subscription.currentPeriodStart();
        Instant periodEnd = command.periodEnd() != null
                ? command.periodEnd().truncatedTo(ChronoUnit.MICROS)
                : subscription.currentPeriodEnd();

        if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) {
            throw new InvalidBillingRequestException(
                    "Billing periodEnd must be strictly after periodStart");
        }

        String idempotencyKey = normalizeOptionalKey(command.idempotencyKey());
        if (idempotencyKey != null) {
            Optional<InvoiceEntity> replay = invoiceRepository.findByCustomerIdAndIdempotencyKey(
                    subscription.customerId(), idempotencyKey);
            if (replay.isPresent()) {
                return loadInvoiceDetail(replay.get());
            }
        }

        int graceHours = command.gracePeriodHours() != null
                ? Math.max(0, command.gracePeriodHours())
                : defaultGracePeriodHours;
        Instant graceExpiresAt = periodEnd.plus(Duration.ofHours(graceHours)).truncatedTo(ChronoUnit.MICROS);
        Instant now = nowMicros();

        Optional<InvoiceEntity> existingActive = invoiceRepository.findActiveBySubscriptionAndPeriod(
                subscription.id(), periodStart, periodEnd);

        InvoiceEntity invoice;
        if (existingActive.isPresent()) {
            invoice = existingActive.get();
            if (invoice.getStatus() != InvoiceStatus.DRAFT) {
                return loadInvoiceDetail(invoice);
            }
            // Release any draft-attached adjustments and replace lines cleanly before recomputation.
            List<BillingAdjustmentEntity> previouslyAttached = adjustmentRepository.findByAppliedInvoiceId(invoice.getId());
            for (BillingAdjustmentEntity adj : previouslyAttached) {
                if (adj.getStatus() == BillingAdjustmentStatus.APPLIED_TO_DRAFT) {
                    adj.releaseToPending(now);
                }
            }
            adjustmentRepository.saveAllAndFlush(previouslyAttached);
            invoiceLineRepository.deleteByInvoiceId(invoice.getId());
            invoiceTaxLineRepository.deleteByInvoiceId(invoice.getId());
            invoiceLineRepository.flush();
            invoiceTaxLineRepository.flush();
        } else {
            invoice = new InvoiceEntity(
                    subscription.customerId(),
                    subscription.id(),
                    command.billingRunId(),
                    periodStart,
                    periodEnd,
                    subscription.billingTimezone(),
                    graceExpiresAt,
                    subscription.currency(),
                    idempotencyKey,
                    now
            );
            invoice = invoiceRepository.saveAndFlush(invoice);
        }

        PlanVersionDetail planVersion = planCatalogFacade.findPlanVersionById(subscription.currentPlanVersionId())
                .orElseThrow(() -> new BillingResourceNotFoundException(
                        "Plan version not found: " + subscription.currentPlanVersionId()));

        Map<UUID, UsageAggregationResult> authoritativeUsageByMetric = queryAuthoritativeUsage(
                subscription.customerId(), planVersion, periodStart, periodEnd);

        List<MetricUsageInput> metricUsageInputs = authoritativeUsageByMetric.values().stream()
                .map(u -> new MetricUsageInput(u.metricId(), u.aggregatedQuantity()))
                .toList();

        PlanRatingResult ratingResult = ratingEngineFacade.rate(new RatingRequest(
                subscription.customerId(),
                planVersion.id(),
                subscription.currency(),
                periodStart,
                periodEnd,
                subscription.seatQuantity(),
                metricUsageInputs,
                null
        ));

        Map<UUID, ChargeDefinition> chargeDefsById = new LinkedHashMap<>();
        for (ChargeDefinition def : planVersion.charges()) {
            chargeDefsById.put(def.id(), def);
        }

        List<InvoiceLineEntity> linesToPersist = new ArrayList<>();
        int lineOrder = 1;

        for (RatedChargeBreakdown breakdown : ratingResult.chargeBreakdowns()) {
            ChargeDefinition def = chargeDefsById.get(breakdown.chargeId());
            String description = def != null && def.description() != null && !def.description().isBlank()
                    ? def.description()
                    : breakdown.chargeCode() + " (" + breakdown.chargeType() + ")";
            InvoiceLineType lineType = breakdown.metricId() == null
                    ? InvoiceLineType.RECURRING_CHARGE
                    : InvoiceLineType.USAGE_CHARGE;

            UsageAggregationResult usageInfo = breakdown.metricId() == null
                    ? null
                    : authoritativeUsageByMetric.get(breakdown.metricId());

            Map<String, Object> provenance = buildChargeProvenance(breakdown, usageInfo, planVersion);

            linesToPersist.add(new InvoiceLineEntity(
                    invoice.getId(),
                    lineOrder++,
                    lineType,
                    truncate(description, 255),
                    subscription.id(),
                    planVersion.id(),
                    breakdown.chargeId(),
                    breakdown.chargeCode(),
                    breakdown.chargeType(),
                    breakdown.metricId(),
                    periodStart,
                    periodEnd,
                    "PER_SEAT".equals(breakdown.chargeType()) ? subscription.seatQuantity() : null,
                    scaleQuantity(breakdown.originalQuantity()),
                    scaleQuantity(breakdown.freeAllowanceQuantity()),
                    scaleQuantity(breakdown.billableQuantity()),
                    breakdown.effectiveUnitPrice(),
                    breakdown.finalPersistedAmount().roundToPersisted().amount(),
                    true,
                    subscription.currency(),
                    breakdown.chargeId(),
                    provenance,
                    now
            ));
        }

        if (ratingResult.planMinimumTrueUp() != null && ratingResult.planMinimumTrueUp().isPositive()) {
            Map<String, Object> trueUpMeta = new LinkedHashMap<>();
            trueUpMeta.put("planCode", planVersion.planCode());
            trueUpMeta.put("versionNumber", planVersion.versionNumber());
            trueUpMeta.put("planMinimumCommitment",
                    ratingResult.planMinimumCommitment() == null ? null : ratingResult.planMinimumCommitment().amountAsPlainString());
            trueUpMeta.put("calculatedUsageAmount", ratingResult.calculatedUsageAmount().amountAsPlainString());
            trueUpMeta.put("trueUpAmount", ratingResult.planMinimumTrueUp().amountAsPlainString());

            linesToPersist.add(new InvoiceLineEntity(
                    invoice.getId(),
                    lineOrder++,
                    InvoiceLineType.MINIMUM_COMMITMENT_TRUE_UP,
                    "Plan minimum commitment true-up (" + planVersion.planCode() + " v" + planVersion.versionNumber() + ")",
                    subscription.id(),
                    planVersion.id(),
                    null,
                    "PLAN_MINIMUM_TRUE_UP",
                    "MINIMUM_COMMITMENT",
                    null,
                    periodStart,
                    periodEnd,
                    null,
                    BigDecimal.ONE.setScale(6),
                    BigDecimal.ZERO.setScale(6),
                    BigDecimal.ONE.setScale(6),
                    null,
                    ratingResult.planMinimumTrueUp().roundToPersisted().amount(),
                    true,
                    subscription.currency(),
                    planVersion.id(),
                    trueUpMeta,
                    now
            ));
        }

        // Include mid-cycle proration records that took effect during this billing period (1 indexed query vs 5-table history).
        List<ProrationRecord> prorationRecords = subscriptionQueryFacade.listProrationRecords(subscription.id());
        for (ProrationRecord proration : prorationRecords) {
            if (proration.currency() == subscription.currency()
                    && !proration.effectiveAt().isBefore(periodStart)
                    && proration.effectiveAt().isBefore(periodEnd)
                    && !proration.netProrationAmount().isZero()) {
                Map<String, Object> prorationMeta = new LinkedHashMap<>();
                prorationMeta.put("prorationRecordId", proration.id().toString());
                prorationMeta.put("previousPlanVersionId", proration.previousPlanVersionId().toString());
                prorationMeta.put("newPlanVersionId", proration.newPlanVersionId().toString());
                prorationMeta.put("previousSeatQuantity", proration.previousSeatQuantity());
                prorationMeta.put("newSeatQuantity", proration.newSeatQuantity());
                prorationMeta.put("prorationFactor", proration.prorationFactor().toPlainString());
                prorationMeta.put("creditAmount", proration.creditAmount().amountAsPlainString());
                prorationMeta.put("debitAmount", proration.debitAmount().amountAsPlainString());
                prorationMeta.put("calculationPolicy", proration.calculationPolicy());

                linesToPersist.add(new InvoiceLineEntity(
                        invoice.getId(),
                        lineOrder++,
                        InvoiceLineType.PRORATION_ADJUSTMENT,
                        "Mid-cycle plan/seat proration adjustment",
                        subscription.id(),
                        proration.newPlanVersionId(),
                        null,
                        "PRORATION",
                        "PRORATION",
                        null,
                        proration.effectiveAt(),
                        proration.periodEnd(),
                        proration.newSeatQuantity(),
                        BigDecimal.ONE.setScale(6),
                        BigDecimal.ZERO.setScale(6),
                        BigDecimal.ONE.setScale(6),
                        null,
                        proration.netProrationAmount().roundToPersisted().amount(),
                        true,
                        subscription.currency(),
                        proration.id(),
                        prorationMeta,
                        now
                ));
            }
        }

        // Attach pending billing adjustments (late usage & explicit manual adjustments).
        List<BillingAdjustmentEntity> pendingAdjustments = adjustmentRepository
                .findBySubscriptionIdAndStatusOrderByCreatedAtAsc(subscription.id(), BillingAdjustmentStatus.PENDING);
        List<BillingAdjustmentEntity> attachedAdjustments = new ArrayList<>();
        for (BillingAdjustmentEntity adj : pendingAdjustments) {
            if (adj.getCurrency() != subscription.currency()) {
                continue;
            }
            InvoiceLineType lineType = adj.getAdjustmentType() == BillingAdjustmentType.LATE_USAGE
                    ? InvoiceLineType.LATE_USAGE_ADJUSTMENT
                    : InvoiceLineType.MANUAL_ADJUSTMENT;

            Map<String, Object> adjMeta = new LinkedHashMap<>();
            adjMeta.put("adjustmentId", adj.getId().toString());
            adjMeta.put("adjustmentType", adj.getAdjustmentType().name());
            if (adj.getOriginalInvoiceId() != null) {
                adjMeta.put("originalInvoiceId", adj.getOriginalInvoiceId().toString());
            }
            if (adj.getLateUsageRecordId() != null) {
                adjMeta.put("lateUsageRecordId", adj.getLateUsageRecordId().toString());
            }
            adjMeta.put("details", adj.getCalculationBreakdown());

            linesToPersist.add(new InvoiceLineEntity(
                    invoice.getId(),
                    lineOrder++,
                    lineType,
                    truncate(adj.getDescription(), 255),
                    subscription.id(),
                    planVersion.id(),
                    null,
                    adj.getAdjustmentType().name(),
                    adj.getAdjustmentType().name(),
                    adj.getMetricId(),
                    periodStart,
                    periodEnd,
                    null,
                    scaleQuantity(adj.getQuantityDelta()),
                    BigDecimal.ZERO.setScale(6),
                    scaleQuantity(adj.getQuantityDelta()),
                    null,
                    adj.getAmount(),
                    true,
                    subscription.currency(),
                    adj.getId(),
                    adjMeta,
                    now
            ));
            adj.attachToDraft(invoice.getId(), now);
            attachedAdjustments.add(adj);
        }

        List<InvoiceLineEntity> savedLines = invoiceLineRepository.saveAllAndFlush(linesToPersist);
        if (!attachedAdjustments.isEmpty()) {
            adjustmentRepository.saveAllAndFlush(attachedAdjustments);
        }

        SupportedCurrency currency = subscription.currency();
        Money zero = Money.zero(currency).roundToPersisted();
        Money subtotal = zero;
        Money taxable = zero;
        for (InvoiceLineEntity line : savedLines) {
            Money lineMoney = Money.of(line.getLineAmount(), currency).roundToPersisted();
            subtotal = subtotal.add(lineMoney).roundToPersisted();
            if (line.isTaxable()) {
                taxable = taxable.add(lineMoney).roundToPersisted();
            }
        }
        Money positiveTaxable = taxable.max(zero).roundToPersisted();

        TaxPolicySummary taxPolicy = taxPolicyService.resolveApplicableTaxPolicy(subscription.customerId());
        List<CalculatedTaxLine> calculatedTaxLines = taxCalculationStrategy.calculateTaxLines(
                new TaxCalculationContext(subscription.customerId(), currency, positiveTaxable, taxPolicy));

        List<InvoiceTaxLineEntity> taxEntities = new ArrayList<>();
        Money totalTax = zero;
        for (CalculatedTaxLine taxLine : calculatedTaxLines) {
            Money lineTax = taxLine.taxAmount().roundToPersisted();
            totalTax = totalTax.add(lineTax).roundToPersisted();
            taxEntities.add(new InvoiceTaxLineEntity(
                    invoice.getId(),
                    taxLine.taxPolicyId(),
                    taxLine.taxName(),
                    taxLine.jurisdiction(),
                    taxLine.taxableAmount().roundToPersisted().amount(),
                    taxLine.ratePercent(),
                    lineTax.amount(),
                    currency,
                    now
            ));
        }
        List<InvoiceTaxLineEntity> savedTaxLines = taxEntities.isEmpty()
                ? List.of()
                : invoiceTaxLineRepository.saveAllAndFlush(taxEntities);

        Money total = subtotal.add(totalTax).roundToPersisted();
        Money amountDue = total.max(zero).roundToPersisted();
        Money payableDue = amountDue.roundToPayable();

        invoice.refreshDraftTotals(
                command.billingRunId(),
                graceExpiresAt,
                subtotal.amount(),
                positiveTaxable.amount(),
                totalTax.amount(),
                total.amount(),
                payableDue.amount(),
                now
        );
        InvoiceEntity savedInvoice = invoiceRepository.saveAndFlush(invoice);
        metrics.recordDraftGenerated(Duration.ofNanos(System.nanoTime() - startNanos));
        LOGGER.debug(
                "Generated draft invoice: invoiceId={} subscriptionId={} currency={} lineCount={} subtotal={} tax={} total={}",
                savedInvoice.getId(), savedInvoice.getSubscriptionId(), savedInvoice.getCurrency(),
                savedLines.size(), savedInvoice.getSubtotalAmount(), savedInvoice.getTaxAmount(), savedInvoice.getTotalAmount());
        return BillingMapper.toInvoiceDetail(savedInvoice, savedLines, savedTaxLines);
    }

    @Override
    @Transactional
    public InvoiceDetail finalizeInvoice(UUID invoiceId, FinalizeInvoiceCommand command) {
        InvoiceEntity finalized = finalizeInvoiceEntity(invoiceId, command);
        return loadInvoiceDetail(finalized);
    }

    @Transactional
    void finalizeInvoiceBatch(UUID invoiceId, FinalizeInvoiceCommand command) {
        finalizeInvoiceEntity(invoiceId, command);
    }

    private InvoiceEntity finalizeInvoiceEntity(UUID invoiceId, FinalizeInvoiceCommand command) {
        long startNanos = System.nanoTime();
        if (invoiceId == null) {
            throw new InvalidBillingRequestException("invoiceId must not be null");
        }

        InvoiceEntity invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.FINALIZED || invoice.getStatus() == InvoiceStatus.PAID) {
            return invoice;
        }
        if (!invoice.getStatus().canTransitionTo(InvoiceStatus.FINALIZED)) {
            throw new InvalidInvoiceTransitionException(
                    "Cannot finalize invoice " + invoiceId + " from status " + invoice.getStatus());
        }

        Instant now = nowMicros();
        Instant dueAt = command != null && command.dueAt() != null
                ? command.dueAt().truncatedTo(ChronoUnit.MICROS)
                : now.plus(Duration.ofDays(defaultPaymentTermsDays)).truncatedTo(ChronoUnit.MICROS);
        boolean applyCredits = command == null || command.applyPrepaidCredits();

        SupportedCurrency currency = invoice.getCurrency();
        Money zero = Money.zero(currency).roundToPersisted();
        Money totalAmount = Money.of(invoice.getTotalAmount(), currency).roundToPersisted();
        Money billableDueBeforeCredits = totalAmount.max(zero).roundToPersisted();

        Money creditsApplied = zero;
        if (applyCredits && billableDueBeforeCredits.isPositive()) {
            creditsApplied = prepaidCreditService.consumeCreditsForInvoice(
                    invoice.getCustomerId(),
                    invoice.getId(),
                    currency,
                    billableDueBeforeCredits,
                    now
            );
        }

        Money remainingDue = billableDueBeforeCredits.subtract(creditsApplied).max(zero).roundToPersisted();
        Money payableDue = remainingDue.roundToPayable();

        long seq = invoiceRepository.nextInvoiceSequenceValue();
        String invoiceNumber = "INV-" + Year.now(ZoneOffset.UTC).getValue() + "-" + seq;

        List<BillingAdjustmentEntity> attached = adjustmentRepository.findByAppliedInvoiceId(invoice.getId());
        for (BillingAdjustmentEntity adj : attached) {
            if (adj.getStatus() == BillingAdjustmentStatus.APPLIED_TO_DRAFT) {
                adj.markBilled(invoice.getId(), now);
            }
        }
        if (!attached.isEmpty()) {
            adjustmentRepository.saveAllAndFlush(attached);
        }

        invoice.finalizeInvoice(
                invoiceNumber,
                creditsApplied.amount(),
                remainingDue.amount(),
                payableDue.amount(),
                now,
                dueAt,
                now
        );
        InvoiceEntity saved = invoiceRepository.saveAndFlush(invoice);

        eventPublisher.publishEvent(new InvoiceFinalizedEvent(
                saved.getId(),
                saved.getInvoiceNumber(),
                saved.getCustomerId(),
                saved.getSubscriptionId(),
                saved.getCurrency(),
                Money.of(saved.getTotalAmount(), saved.getCurrency()),
                Money.of(saved.getCreditsAppliedAmount(), saved.getCurrency()),
                Money.of(saved.getPayableAmountDue(), saved.getCurrency()),
                saved.getPeriodStart(),
                saved.getPeriodEnd(),
                saved.getFinalizedAt(),
                saved.getDueAt()
        ));

        metrics.recordInvoiceFinalized(Duration.ofNanos(System.nanoTime() - startNanos));
        LOGGER.debug(
                "Finalized invoice: invoiceId={} invoiceNumber={} status={} total={} creditsApplied={} amountDue={}",
                saved.getId(), saved.getInvoiceNumber(), saved.getStatus(),
                saved.getTotalAmount(), saved.getCreditsAppliedAmount(), saved.getAmountDue());
        return saved;
    }

    @Override
    @Transactional
    public InvoiceDetail voidInvoice(UUID invoiceId, VoidInvoiceCommand command) {
        if (invoiceId == null) {
            throw new InvalidBillingRequestException("invoiceId must not be null");
        }
        String reason = command == null || command.reason() == null || command.reason().isBlank()
                ? "Voided by operator"
                : command.reason().trim();

        InvoiceEntity invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.VOID) {
            return loadInvoiceDetail(invoice);
        }
        if (!invoice.getStatus().canTransitionTo(InvoiceStatus.VOID)) {
            throw new InvalidInvoiceTransitionException(
                    "Cannot void invoice " + invoiceId + " from status " + invoice.getStatus());
        }
        if (invoice.getStatus() != InvoiceStatus.DRAFT
                && (invoice.getCreditsAppliedAmount().compareTo(BigDecimal.ZERO) > 0
                || invoice.getCreditNotesAppliedAmount().compareTo(BigDecimal.ZERO) > 0)) {
            throw new InvalidInvoiceTransitionException(
                    "Cannot void finalized invoice with applied credits or credit notes; issue a credit note instead");
        }

        Instant now = nowMicros();
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            List<BillingAdjustmentEntity> attached = adjustmentRepository.findByAppliedInvoiceId(invoice.getId());
            for (BillingAdjustmentEntity adj : attached) {
                if (adj.getStatus() == BillingAdjustmentStatus.APPLIED_TO_DRAFT) {
                    adj.releaseToPending(now);
                }
            }
            if (!attached.isEmpty()) {
                adjustmentRepository.saveAllAndFlush(attached);
            }
        }

        invoice.markVoid(truncate(reason, 255), now);
        InvoiceEntity saved = invoiceRepository.saveAndFlush(invoice);
        metrics.recordInvoiceVoided();
        LOGGER.info("Voided invoice: invoiceId={} invoiceNumber={}", saved.getId(), saved.getInvoiceNumber());
        return loadInvoiceDetail(saved);
    }

    @Override
    @Transactional
    public InvoiceSummary recordPaymentSettlement(
            UUID invoiceId,
            Money settledAmount,
            String paymentReference,
            String idempotencyKey
    ) {
        if (invoiceId == null || settledAmount == null) {
            throw new InvalidBillingRequestException("invoiceId and settledAmount must not be null");
        }
        InvoiceEntity invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getCurrency() != settledAmount.currency()) {
            throw new com.meterflow.shared.CurrencyMismatchException(invoice.getCurrency(), settledAmount.currency());
        }
        if (invoice.getStatus() != InvoiceStatus.FINALIZED && invoice.getStatus() != InvoiceStatus.PAST_DUE) {
            throw new InvalidInvoiceTransitionException(
                    "Invoice " + invoiceId + " is not eligible for payment settlement from status " + invoice.getStatus());
        }

        Money normalizedPayment = settledAmount.roundToPersisted();
        if (!normalizedPayment.isPositive()) {
            throw new InvalidBillingRequestException("Settled payment amount must be positive");
        }
        Money currentDue = Money.of(invoice.getAmountDue(), invoice.getCurrency()).roundToPersisted();
        if (!currentDue.isPositive()) {
            throw new InvalidInvoiceTransitionException("Invoice " + invoiceId + " has zero remaining balance due");
        }
        if (normalizedPayment.isGreaterThan(currentDue)) {
            throw new InvalidBillingRequestException(
                    "Payment amount " + normalizedPayment + " exceeds remaining invoice balance due " + currentDue);
        }

        Money newDue = currentDue.subtract(normalizedPayment).roundToPersisted();
        Money newPayableDue = newDue.roundToPayable();
        Instant now = nowMicros();
        invoice.recordPaymentSettlement(newDue.amount(), newPayableDue.amount(), now);
        InvoiceEntity saved = invoiceRepository.saveAndFlush(invoice);
        LOGGER.info(
                "Recorded payment settlement on invoice: invoiceId={} invoiceNumber={} paymentRef={} settled={} remainingDue={} status={}",
                saved.getId(), saved.getInvoiceNumber(), paymentReference,
                normalizedPayment, newDue, saved.getStatus());
        return BillingMapper.toInvoiceSummary(saved);
    }

    @Override
    @Transactional
    public InvoiceSummary markInvoicePastDue(UUID invoiceId, String reason) {
        if (invoiceId == null) {
            throw new InvalidBillingRequestException("invoiceId must not be null");
        }
        InvoiceEntity invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Invoice not found: " + invoiceId));

        if (invoice.getStatus() == InvoiceStatus.PAST_DUE) {
            return BillingMapper.toInvoiceSummary(invoice);
        }
        if (!invoice.getStatus().canTransitionTo(InvoiceStatus.PAST_DUE)) {
            throw new InvalidInvoiceTransitionException(
                    "Cannot mark invoice " + invoiceId + " PAST_DUE from status " + invoice.getStatus());
        }
        invoice.markPastDue(nowMicros());
        InvoiceEntity saved = invoiceRepository.saveAndFlush(invoice);
        LOGGER.info("Marked invoice PAST_DUE: invoiceId={} invoiceNumber={}", saved.getId(), saved.getInvoiceNumber());
        return BillingMapper.toInvoiceSummary(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceSummary> findInvoiceById(UUID invoiceId) {
        return invoiceRepository.findById(invoiceId).map(BillingMapper::toInvoiceSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceDetail getInvoiceDetail(UUID invoiceId) {
        InvoiceEntity invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Invoice not found: " + invoiceId));
        return loadInvoiceDetail(invoice);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceSummary> listCustomerInvoices(UUID customerId, InvoiceStatus status, Pageable pageable) {
        return invoiceRepository.findCustomerInvoices(customerId, status, pageable)
                .map(BillingMapper::toInvoiceSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<InvoiceSummary> listInvoices(InvoiceStatus status, Pageable pageable) {
        return invoiceRepository.findAllInvoices(status, pageable)
                .map(BillingMapper::toInvoiceSummary);
    }

    private InvoiceDetail loadInvoiceDetail(InvoiceEntity invoice) {
        List<InvoiceLineEntity> lines = invoiceLineRepository.findByInvoiceIdOrderByLineOrderAsc(invoice.getId());
        List<InvoiceTaxLineEntity> taxLines = invoiceTaxLineRepository.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId());
        return BillingMapper.toInvoiceDetail(invoice, lines, taxLines);
    }

    private Map<UUID, UsageAggregationResult> queryAuthoritativeUsage(
            UUID customerId,
            PlanVersionDetail planVersion,
            Instant periodStart,
            Instant periodEnd
    ) {
        Set<UUID> metricIds = new LinkedHashSet<>();
        for (ChargeDefinition charge : planVersion.charges()) {
            if (charge.metricId() != null) {
                metricIds.add(charge.metricId());
            }
        }

        Map<UUID, UsageAggregationResult> results = new LinkedHashMap<>();
        for (UUID metricId : metricIds) {
            UsageAggregationResult usage = usageQueryFacade.queryMetricUsage(
                    new UsageQueryRequest(customerId, metricId, periodStart, periodEnd, List.of(), true));
            results.put(metricId, usage);
        }
        return results;
    }

    private static Map<String, Object> buildChargeProvenance(
            RatedChargeBreakdown breakdown,
            UsageAggregationResult usageInfo,
            PlanVersionDetail planVersion
    ) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planCode", planVersion.planCode());
        map.put("versionNumber", planVersion.versionNumber());
        map.put("calculatedAmount", breakdown.calculatedAmount().amountAsPlainString());
        if (breakdown.chargeMinimumCommitment() != null) {
            map.put("chargeMinimumCommitment", breakdown.chargeMinimumCommitment().amountAsPlainString());
        }
        if (breakdown.chargeMinimumTrueUp() != null) {
            map.put("chargeMinimumTrueUp", breakdown.chargeMinimumTrueUp().amountAsPlainString());
        }
        map.put("finalPersistedAmount", breakdown.finalPersistedAmount().amountAsPlainString());

        if (!breakdown.appliedTiers().isEmpty()) {
            List<Map<String, Object>> tiers = new ArrayList<>();
            for (AppliedTierBreakdown tier : breakdown.appliedTiers()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("tierOrder", tier.tierOrder());
                t.put("lowerBound", tier.lowerBound() == null ? null : tier.lowerBound().toPlainString());
                t.put("upperBound", tier.upperBound() == null ? null : tier.upperBound().toPlainString());
                t.put("unitsBilled", tier.unitsBilled() == null ? null : tier.unitsBilled().toPlainString());
                t.put("unitPrice", tier.unitPrice() == null ? null : tier.unitPrice().toPlainString());
                t.put("flatFee", tier.flatFee() == null ? null : tier.flatFee().toPlainString());
                t.put("tierAmount", tier.tierAmount().amountAsPlainString());
                tiers.add(t);
            }
            map.put("appliedTiers", tiers);
        }

        if (usageInfo != null) {
            map.put("metricKey", usageInfo.metricKey());
            map.put("aggregationType", usageInfo.aggregationType().name());
            map.put("dataSource", usageInfo.dataSource().name());
            map.put("matchedEventCount", usageInfo.matchedEventCount());
            map.put("lateEventCount", usageInfo.lateEventCount());
            map.put("traceableEventIds", usageInfo.traceableEventIds().stream().map(UUID::toString).toList());
        }

        if (!breakdown.metadata().isEmpty()) {
            map.put("ratingMetadata", breakdown.metadata());
        }
        return map;
    }

    private static BigDecimal scaleQuantity(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(6);
        }
        return value.setScale(6, Money.ROUNDING_MODE);
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }

    private static String normalizeOptionalKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
