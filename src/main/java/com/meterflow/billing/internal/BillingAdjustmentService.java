package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingAdjustmentFacade;
import com.meterflow.billing.api.BillingAdjustmentRecord;
import com.meterflow.billing.api.BillingAdjustmentType;
import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.CreateManualAdjustmentCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.LateUsageProcessingSummary;
import com.meterflow.catalog.api.ChargeDefinition;
import com.meterflow.catalog.api.MetricUsageInput;
import com.meterflow.catalog.api.PlanCatalogFacade;
import com.meterflow.catalog.api.PlanRatingResult;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.RatedChargeBreakdown;
import com.meterflow.catalog.api.RatingEngineFacade;
import com.meterflow.catalog.api.RatingRequest;
import com.meterflow.metering.api.LateUsageEventRecord;
import com.meterflow.metering.api.LateUsageQueryFacade;
import com.meterflow.metering.api.UsageAggregationResult;
import com.meterflow.metering.api.UsageQueryFacade;
import com.meterflow.metering.api.UsageQueryRequest;
import com.meterflow.shared.CurrencyMismatchException;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.math.BigDecimal;
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

@Service
class BillingAdjustmentService implements BillingAdjustmentFacade {

    private static final int LATE_USAGE_BATCH_SIZE = 500;

    private final BillingAdjustmentRepository adjustmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceLineRepository invoiceLineRepository;
    private final InvoiceManagementService invoiceManagementService;
    private final LateUsageQueryFacade lateUsageQueryFacade;
    private final UsageQueryFacade usageQueryFacade;
    private final SubscriptionQueryFacade subscriptionQueryFacade;
    private final PlanCatalogFacade planCatalogFacade;
    private final RatingEngineFacade ratingEngineFacade;
    private final BillingMetrics metrics;

    BillingAdjustmentService(
            BillingAdjustmentRepository adjustmentRepository,
            InvoiceRepository invoiceRepository,
            InvoiceLineRepository invoiceLineRepository,
            InvoiceManagementService invoiceManagementService,
            LateUsageQueryFacade lateUsageQueryFacade,
            UsageQueryFacade usageQueryFacade,
            SubscriptionQueryFacade subscriptionQueryFacade,
            PlanCatalogFacade planCatalogFacade,
            RatingEngineFacade ratingEngineFacade,
            BillingMetrics metrics
    ) {
        this.adjustmentRepository = adjustmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceLineRepository = invoiceLineRepository;
        this.invoiceManagementService = invoiceManagementService;
        this.lateUsageQueryFacade = lateUsageQueryFacade;
        this.usageQueryFacade = usageQueryFacade;
        this.subscriptionQueryFacade = subscriptionQueryFacade;
        this.planCatalogFacade = planCatalogFacade;
        this.ratingEngineFacade = ratingEngineFacade;
        this.metrics = metrics;
    }

    @Override
    @Transactional
    public LateUsageProcessingSummary processPendingLateUsage(UUID customerId) {
        Page<LateUsageEventRecord> pendingPage = lateUsageQueryFacade.listPendingLateEvents(
                customerId, PageRequest.of(0, LATE_USAGE_BATCH_SIZE));
        List<LateUsageEventRecord> records = pendingPage.getContent();

        int evaluated = 0;
        int draftsRefreshed = 0;
        int adjustmentsCreated = 0;
        List<UUID> createdAdjustmentIds = new ArrayList<>();
        Set<UUID> toMarkConsumed = new LinkedHashSet<>();

        Instant now = nowMicros();

        for (LateUsageEventRecord record : records) {
            evaluated++;
            if (adjustmentRepository.findByLateUsageRecordId(record.id()).isPresent()) {
                toMarkConsumed.add(record.id());
                continue;
            }

            Optional<InvoiceEntity> invoiceOpt = invoiceRepository.findActiveBySubscriptionAndPeriodEnd(
                    record.subscriptionId(), record.closedPeriodEnd());
            if (invoiceOpt.isEmpty()) {
                // Period has not been billed yet; regular draft generation will include this usage event directly.
                continue;
            }

            InvoiceEntity originalInvoice = invoiceOpt.get();
            if (originalInvoice.getStatus() == InvoiceStatus.DRAFT) {
                invoiceManagementService.generateOrRefreshDraft(new GenerateDraftInvoiceCommand(
                        record.subscriptionId(),
                        originalInvoice.getPeriodStart(),
                        originalInvoice.getPeriodEnd(),
                        null,
                        null,
                        null
                ));
                draftsRefreshed++;
                toMarkConsumed.add(record.id());
                continue;
            }

            // Original invoice is already finalized: compute cumulative authoritative delta without modifying it.
            SubscriptionSummary subscription = subscriptionQueryFacade.findSubscriptionById(record.subscriptionId())
                    .orElseThrow(() -> new BillingResourceNotFoundException(
                            "Subscription not found: " + record.subscriptionId()));

            List<InvoiceLineEntity> originalLines =
                    invoiceLineRepository.findByInvoiceIdOrderByLineOrderAsc(originalInvoice.getId());

            UUID planVersionId = originalLines.stream()
                    .map(InvoiceLineEntity::getPlanVersionId)
                    .filter(id -> id != null)
                    .findFirst()
                    .orElse(subscription.currentPlanVersionId());

            PlanVersionDetail planVersion = planCatalogFacade.findPlanVersionById(planVersionId)
                    .orElseThrow(() -> new BillingResourceNotFoundException(
                            "Plan version not found: " + planVersionId));

            Set<UUID> metricIds = new LinkedHashSet<>();
            for (ChargeDefinition charge : planVersion.charges()) {
                if (charge.metricId() != null) {
                    metricIds.add(charge.metricId());
                }
            }
            metricIds.add(record.metricId());

            Map<UUID, UsageAggregationResult> usageByMetric = new LinkedHashMap<>();
            for (UUID metricId : metricIds) {
                usageByMetric.put(metricId, usageQueryFacade.queryMetricUsage(new UsageQueryRequest(
                        record.customerId(),
                        metricId,
                        originalInvoice.getPeriodStart(),
                        originalInvoice.getPeriodEnd(),
                        List.of(),
                        false
                )));
            }

            List<MetricUsageInput> usageInputs = usageByMetric.values().stream()
                    .map(u -> new MetricUsageInput(u.metricId(), u.aggregatedQuantity()))
                    .toList();

            PlanRatingResult reRated = ratingEngineFacade.rate(new RatingRequest(
                    record.customerId(),
                    planVersion.id(),
                    originalInvoice.getCurrency(),
                    originalInvoice.getPeriodStart(),
                    originalInvoice.getPeriodEnd(),
                    subscription.seatQuantity(),
                    usageInputs,
                    null
            ));

            SupportedCurrency currency = originalInvoice.getCurrency();
            Money zero = Money.zero(currency).roundToPersisted();

            Money reRatedMetricTotal = zero;
            for (RatedChargeBreakdown breakdown : reRated.chargeBreakdowns()) {
                if (record.metricId().equals(breakdown.metricId())) {
                    reRatedMetricTotal = reRatedMetricTotal
                            .add(breakdown.finalPersistedAmount().roundToPersisted())
                            .roundToPersisted();
                }
            }

            Money previouslyBilledAmount = zero;
            BigDecimal previouslyBilledQty = BigDecimal.ZERO;
            for (InvoiceLineEntity line : originalLines) {
                if (record.metricId().equals(line.getMetricId())) {
                    previouslyBilledAmount = previouslyBilledAmount
                            .add(Money.of(line.getLineAmount(), currency).roundToPersisted())
                            .roundToPersisted();
                    previouslyBilledQty = previouslyBilledQty.add(line.getRawQuantity());
                }
            }

            List<BillingAdjustmentEntity> priorAdjustments =
                    adjustmentRepository.findByOriginalInvoiceIdAndMetricId(originalInvoice.getId(), record.metricId());
            for (BillingAdjustmentEntity prior : priorAdjustments) {
                previouslyBilledAmount = previouslyBilledAmount
                        .add(Money.of(prior.getAmount(), currency).roundToPersisted())
                        .roundToPersisted();
                previouslyBilledQty = previouslyBilledQty.add(prior.getQuantityDelta());
            }

            Money deltaAmount = reRatedMetricTotal.subtract(previouslyBilledAmount).roundToPersisted();
            UsageAggregationResult targetUsage = usageByMetric.get(record.metricId());
            BigDecimal cumulativeQty = targetUsage == null ? BigDecimal.ZERO : targetUsage.aggregatedQuantity();
            BigDecimal quantityDelta = cumulativeQty.subtract(previouslyBilledQty).setScale(6, Money.ROUNDING_MODE);

            if (!deltaAmount.isZero()) {
                Map<String, Object> breakdown = new LinkedHashMap<>();
                breakdown.put("originalInvoiceNumber", originalInvoice.getInvoiceNumber());
                breakdown.put("usageEventId", record.usageEventId().toString());
                breakdown.put("closedPeriodStart", originalInvoice.getPeriodStart().toString());
                breakdown.put("closedPeriodEnd", originalInvoice.getPeriodEnd().toString());
                breakdown.put("hoursLate", record.hoursLate().toPlainString());
                breakdown.put("cumulativeQuantity", cumulativeQty.toPlainString());
                breakdown.put("previouslyBilledQuantity", previouslyBilledQty.toPlainString());
                breakdown.put("previouslyBilledAmount", previouslyBilledAmount.amountAsPlainString());
                breakdown.put("reRatedMetricAmount", reRatedMetricTotal.amountAsPlainString());
                breakdown.put("deltaAmount", deltaAmount.amountAsPlainString());

                BillingAdjustmentEntity adjustment = new BillingAdjustmentEntity(
                        record.customerId(),
                        record.subscriptionId(),
                        originalInvoice.getId(),
                        record.id(),
                        record.metricId(),
                        BillingAdjustmentType.LATE_USAGE,
                        "Late usage adjustment for finalized invoice " + originalInvoice.getInvoiceNumber(),
                        quantityDelta,
                        deltaAmount.amount(),
                        currency,
                        "late-usage:" + record.id(),
                        breakdown,
                        now
                );
                BillingAdjustmentEntity saved = adjustmentRepository.saveAndFlush(adjustment);
                createdAdjustmentIds.add(saved.getId());
                adjustmentsCreated++;
                metrics.recordAdjustmentCreated();
            }

            toMarkConsumed.add(record.id());
        }

        int markedConsumed = toMarkConsumed.isEmpty()
                ? 0
                : lateUsageQueryFacade.markLateEventsConsumed(toMarkConsumed);

        return new LateUsageProcessingSummary(
                evaluated,
                draftsRefreshed,
                adjustmentsCreated,
                markedConsumed,
                createdAdjustmentIds
        );
    }

    @Override
    @Transactional
    public BillingAdjustmentRecord createManualAdjustment(CreateManualAdjustmentCommand command) {
        if (command == null) {
            throw new InvalidBillingRequestException("Manual adjustment command must not be null");
        }
        if (command.customerId() == null || command.subscriptionId() == null) {
            throw new InvalidBillingRequestException("customerId and subscriptionId must not be null");
        }
        if (command.adjustmentType() == null || command.adjustmentType() == BillingAdjustmentType.LATE_USAGE) {
            throw new InvalidBillingRequestException("Manual adjustment type must be MANUAL_CREDIT or MANUAL_DEBIT");
        }
        if (command.description() == null || command.description().isBlank()) {
            throw new InvalidBillingRequestException("description must not be blank");
        }
        if (command.amount() == null || command.currency() == null) {
            throw new InvalidBillingRequestException("amount and currency must not be null");
        }

        String idempotencyKey = normalizeOptionalKey(command.idempotencyKey());
        if (idempotencyKey != null) {
            var existing = adjustmentRepository.findByCustomerIdAndIdempotencyKey(command.customerId(), idempotencyKey);
            if (existing.isPresent()) {
                return BillingMapper.toBillingAdjustmentRecord(existing.get());
            }
        }

        SubscriptionSummary subscription = subscriptionQueryFacade.findSubscriptionById(command.subscriptionId())
                .orElseThrow(() -> new BillingResourceNotFoundException(
                        "Subscription not found: " + command.subscriptionId()));
        if (!subscription.customerId().equals(command.customerId())) {
            throw new InvalidBillingRequestException("Subscription does not belong to customer");
        }
        if (subscription.currency() != command.currency()) {
            throw new CurrencyMismatchException(subscription.currency(), command.currency());
        }

        if (command.originalInvoiceId() != null) {
            InvoiceEntity orig = invoiceRepository.findById(command.originalInvoiceId())
                    .orElseThrow(() -> new BillingResourceNotFoundException(
                            "Original invoice not found: " + command.originalInvoiceId()));
            if (!orig.getCustomerId().equals(command.customerId())) {
                throw new InvalidBillingRequestException("Original invoice does not belong to customer");
            }
        }

        Money absAmount = Money.of(command.amount(), command.currency()).abs().roundToPersisted();
        if (!absAmount.isPositive()) {
            throw new InvalidBillingRequestException("Adjustment amount must be non-zero");
        }
        Money signedAmount = command.adjustmentType() == BillingAdjustmentType.MANUAL_CREDIT
                ? absAmount.negate().roundToPersisted()
                : absAmount;

        Instant now = nowMicros();
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("adjustmentType", command.adjustmentType().name());
        breakdown.put("signedAmount", signedAmount.amountAsPlainString());

        BillingAdjustmentEntity entity = new BillingAdjustmentEntity(
                command.customerId(),
                command.subscriptionId(),
                command.originalInvoiceId(),
                null,
                null,
                command.adjustmentType(),
                command.description().trim(),
                BigDecimal.ZERO.setScale(6),
                signedAmount.amount(),
                command.currency(),
                idempotencyKey,
                breakdown,
                now
        );
        BillingAdjustmentEntity saved = adjustmentRepository.saveAndFlush(entity);
        metrics.recordAdjustmentCreated();
        return BillingMapper.toBillingAdjustmentRecord(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingAdjustmentRecord> listCustomerAdjustments(UUID customerId, Pageable pageable) {
        return adjustmentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
                .map(BillingMapper::toBillingAdjustmentRecord);
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
