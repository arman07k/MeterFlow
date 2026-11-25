package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingAdjustmentRecord;
import com.meterflow.billing.api.BillingRunSummary;
import com.meterflow.billing.api.CreditGrantSummary;
import com.meterflow.billing.api.CreditNoteDetail;
import com.meterflow.billing.api.CreditNoteLineRecord;
import com.meterflow.billing.api.CreditTransactionRecord;
import com.meterflow.billing.api.InvoiceDetail;
import com.meterflow.billing.api.InvoiceLineRecord;
import com.meterflow.billing.api.InvoiceSummary;
import com.meterflow.billing.api.InvoiceTaxLineRecord;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.shared.Money;
import java.util.List;

final class BillingMapper {

    private BillingMapper() {
    }

    static TaxPolicySummary toTaxPolicySummary(TaxPolicyEntity e) {
        return new TaxPolicySummary(
                e.getId(),
                e.getPolicyCode(),
                e.getName(),
                e.getCustomerId(),
                e.getJurisdiction(),
                e.getTaxName(),
                e.getRatePercent(),
                e.isTaxExempt(),
                e.isActive(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    static BillingRunSummary toBillingRunSummary(BillingRunEntity e) {
        return new BillingRunSummary(
                e.getId(),
                e.getBatchJobExecutionId(),
                e.getRunType(),
                e.getCustomerId(),
                e.getCutoffTimestamp(),
                e.getIdempotencyKey(),
                e.getStatus(),
                e.getProcessedCount(),
                e.getCreatedCount(),
                e.getSkippedCount(),
                e.getFailedCount(),
                e.getErrorSummary(),
                e.getStartedAt(),
                e.getCompletedAt()
        );
    }

    static InvoiceSummary toInvoiceSummary(InvoiceEntity e) {
        return new InvoiceSummary(
                e.getId(),
                e.getInvoiceNumber(),
                e.getCustomerId(),
                e.getSubscriptionId(),
                e.getBillingRunId(),
                e.getPeriodStart(),
                e.getPeriodEnd(),
                e.getBillingTimezone(),
                e.getGraceExpiresAt(),
                e.getCurrency(),
                Money.of(e.getSubtotalAmount(), e.getCurrency()),
                Money.of(e.getTaxableAmount(), e.getCurrency()),
                Money.of(e.getTaxAmount(), e.getCurrency()),
                Money.of(e.getTotalAmount(), e.getCurrency()),
                Money.of(e.getCreditsAppliedAmount(), e.getCurrency()),
                Money.of(e.getCreditNotesAppliedAmount(), e.getCurrency()),
                Money.of(e.getAmountDue(), e.getCurrency()),
                Money.of(e.getPayableAmountDue(), e.getCurrency()),
                e.getStatus(),
                e.getFinalizedAt(),
                e.getDueAt(),
                e.getVoidedAt(),
                e.getVoidReason(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    static InvoiceLineRecord toInvoiceLineRecord(InvoiceLineEntity e) {
        return new InvoiceLineRecord(
                e.getId(),
                e.getInvoiceId(),
                e.getLineOrder(),
                e.getLineType(),
                e.getDescription(),
                e.getSubscriptionId(),
                e.getPlanVersionId(),
                e.getChargeId(),
                e.getChargeCode(),
                e.getChargeType(),
                e.getMetricId(),
                e.getWindowStart(),
                e.getWindowEnd(),
                e.getSeatQuantity(),
                e.getRawQuantity(),
                e.getFreeAllowanceQuantity(),
                e.getBillableQuantity(),
                e.getUnitPrice(),
                Money.of(e.getLineAmount(), e.getCurrency()),
                e.isTaxable(),
                e.getSourceReferenceId(),
                e.getCalculationBreakdown(),
                e.getCreatedAt()
        );
    }

    static InvoiceTaxLineRecord toInvoiceTaxLineRecord(InvoiceTaxLineEntity e) {
        return new InvoiceTaxLineRecord(
                e.getId(),
                e.getInvoiceId(),
                e.getTaxPolicyId(),
                e.getTaxName(),
                e.getJurisdiction(),
                Money.of(e.getTaxableAmount(), e.getCurrency()),
                e.getRatePercent(),
                Money.of(e.getTaxAmount(), e.getCurrency()),
                e.getCreatedAt()
        );
    }

    static InvoiceDetail toInvoiceDetail(
            InvoiceEntity invoice,
            List<InvoiceLineEntity> lines,
            List<InvoiceTaxLineEntity> taxLines
    ) {
        return new InvoiceDetail(
                toInvoiceSummary(invoice),
                lines.stream().map(BillingMapper::toInvoiceLineRecord).toList(),
                taxLines.stream().map(BillingMapper::toInvoiceTaxLineRecord).toList()
        );
    }

    static CreditGrantSummary toCreditGrantSummary(PrepaidCreditGrantEntity e) {
        return new CreditGrantSummary(
                e.getId(),
                e.getCustomerId(),
                e.getCurrency(),
                e.getGrantType(),
                e.getDescription(),
                Money.of(e.getOriginalAmount(), e.getCurrency()),
                Money.of(e.getRemainingAmount(), e.getCurrency()),
                e.getPriority(),
                e.getExpiresAt(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    static CreditTransactionRecord toCreditTransactionRecord(CreditTransactionEntity e) {
        return new CreditTransactionRecord(
                e.getId(),
                e.getCreditGrantId(),
                e.getCustomerId(),
                e.getInvoiceId(),
                e.getTransactionType(),
                Money.of(e.getAmount(), e.getCurrency()),
                Money.of(e.getBalanceAfter(), e.getCurrency()),
                e.getReason(),
                e.getIdempotencyKey(),
                e.getCreatedAt()
        );
    }

    static CreditNoteDetail toCreditNoteDetail(CreditNoteEntity cn, List<CreditNoteLineEntity> lines) {
        List<CreditNoteLineRecord> mappedLines = lines.stream()
                .map(l -> new CreditNoteLineRecord(
                        l.getId(),
                        l.getCreditNoteId(),
                        l.getOriginalInvoiceLineId(),
                        l.getDescription(),
                        Money.of(l.getCreditAmount(), l.getCurrency()),
                        Money.of(l.getTaxAmount(), l.getCurrency()),
                        l.getCreatedAt()
                ))
                .toList();
        return new CreditNoteDetail(
                cn.getId(),
                cn.getCreditNoteNumber(),
                cn.getOriginalInvoiceId(),
                cn.getCustomerId(),
                cn.getCurrency(),
                cn.getCreditType(),
                cn.getReason(),
                Money.of(cn.getSubtotalCredit(), cn.getCurrency()),
                Money.of(cn.getTaxCredit(), cn.getCurrency()),
                Money.of(cn.getTotalCredit(), cn.getCurrency()),
                Money.of(cn.getAppliedToInvoiceDue(), cn.getCurrency()),
                Money.of(cn.getRefundedToCreditWallet(), cn.getCurrency()),
                cn.getIssuedAt(),
                mappedLines
        );
    }

    static BillingAdjustmentRecord toBillingAdjustmentRecord(BillingAdjustmentEntity e) {
        return new BillingAdjustmentRecord(
                e.getId(),
                e.getCustomerId(),
                e.getSubscriptionId(),
                e.getOriginalInvoiceId(),
                e.getAppliedInvoiceId(),
                e.getLateUsageRecordId(),
                e.getMetricId(),
                e.getAdjustmentType(),
                e.getDescription(),
                e.getQuantityDelta(),
                Money.of(e.getAmount(), e.getCurrency()),
                e.getStatus(),
                e.getCalculationBreakdown(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
