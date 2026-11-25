package com.meterflow.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterflow.billing.api.BillingAdjustmentStatus;
import com.meterflow.billing.api.BillingAdjustmentType;
import com.meterflow.billing.api.BillingRunStatus;
import com.meterflow.billing.api.BillingRunType;
import com.meterflow.billing.api.CalculatedTaxLine;
import com.meterflow.billing.api.CreditGrantType;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.billing.api.TaxCalculationContext;
import com.meterflow.billing.api.TaxPolicySummary;
import com.meterflow.shared.Money;
import com.meterflow.shared.SupportedCurrency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillingUnitTests {

    private final StandardPercentageTaxStrategy taxStrategy = new StandardPercentageTaxStrategy();

    @Test
    void invoiceStatusEnforcesAuthoritativeTransitionAndImmutabilityRules() {
        assertThat(InvoiceStatus.DRAFT.isImmutableFinancialRecord()).isFalse();
        assertThat(InvoiceStatus.DRAFT.isEligibleForCreditNote()).isFalse();
        assertThat(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.FINALIZED)).isTrue();
        assertThat(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.VOID)).isTrue();
        assertThat(InvoiceStatus.DRAFT.canTransitionTo(InvoiceStatus.PAID)).isFalse();

        assertThat(InvoiceStatus.FINALIZED.isImmutableFinancialRecord()).isTrue();
        assertThat(InvoiceStatus.FINALIZED.isEligibleForCreditNote()).isTrue();
        assertThat(InvoiceStatus.FINALIZED.canTransitionTo(InvoiceStatus.DRAFT)).isFalse();
        assertThat(InvoiceStatus.FINALIZED.canTransitionTo(InvoiceStatus.PAID)).isTrue();
        assertThat(InvoiceStatus.FINALIZED.canTransitionTo(InvoiceStatus.PAST_DUE)).isTrue();
        assertThat(InvoiceStatus.FINALIZED.canTransitionTo(InvoiceStatus.VOID)).isTrue();

        assertThat(InvoiceStatus.PAID.isEligibleForCreditNote()).isTrue();
        assertThat(InvoiceStatus.PAID.canTransitionTo(InvoiceStatus.VOID)).isFalse();
        assertThat(InvoiceStatus.VOID.isEligibleForCreditNote()).isFalse();
        assertThat(InvoiceStatus.VOID.allowedTargets()).isEmpty();
        assertThat(InvoiceStatus.WRITTEN_OFF.allowedTargets()).isEmpty();
    }

    @Test
    void standardPercentageTaxStrategyComputesDeterministicExactHalfUpTaxForBdtAndUsd() {
        UUID policyId = UUID.randomUUID();
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        TaxPolicySummary vat15 = new TaxPolicySummary(
                policyId, "BD_VAT_15", "Bangladesh Standard VAT", null, "BD", "VAT",
                new BigDecimal("15.0000"), false, true, now, now
        );

        // 1150.3333 * 15% = 172.549995 -> HALF_UP to scale=4 = 172.5500
        List<CalculatedTaxLine> bdtLines = taxStrategy.calculateTaxLines(new TaxCalculationContext(
                UUID.randomUUID(),
                SupportedCurrency.BDT,
                Money.of(new BigDecimal("1150.3333"), SupportedCurrency.BDT),
                vat15
        ));
        assertThat(bdtLines).hasSize(1);
        assertThat(bdtLines.getFirst().taxAmount()).isEqualTo(Money.bdt("172.5500"));
        assertThat(bdtLines.getFirst().taxPolicyId()).isEqualTo(policyId);

        // 33.3333 * 7.5% = 2.4999975 -> HALF_UP to scale=4 = 2.5000
        TaxPolicySummary usTax = new TaxPolicySummary(
                policyId, "US_SALES_7_5", "US Sales Tax", null, "US", "Sales Tax",
                new BigDecimal("7.5000"), false, true, now, now
        );
        List<CalculatedTaxLine> usdLines = taxStrategy.calculateTaxLines(new TaxCalculationContext(
                UUID.randomUUID(),
                SupportedCurrency.USD,
                Money.of(new BigDecimal("33.3333"), SupportedCurrency.USD),
                usTax
        ));
        assertThat(usdLines).hasSize(1);
        assertThat(usdLines.getFirst().taxAmount()).isEqualTo(Money.usd("2.5000"));
    }

    @Test
    void standardPercentageTaxStrategyProducesZeroTaxLinesForExemptZeroOrNegativeTaxable() {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        TaxPolicySummary exemptPolicy = new TaxPolicySummary(
                UUID.randomUUID(), "EXEMPT", "Tax Exempt", UUID.randomUUID(), "BD", "VAT",
                new BigDecimal("15.0000"), true, true, now, now
        );
        TaxPolicySummary standardPolicy = new TaxPolicySummary(
                UUID.randomUUID(), "STD", "Standard VAT", null, "BD", "VAT",
                new BigDecimal("15.0000"), false, true, now, now
        );

        assertThat(taxStrategy.calculateTaxLines(new TaxCalculationContext(
                UUID.randomUUID(), SupportedCurrency.BDT, Money.bdt("500.0000"), exemptPolicy
        ))).isEmpty();

        assertThat(taxStrategy.calculateTaxLines(new TaxCalculationContext(
                UUID.randomUUID(), SupportedCurrency.BDT, Money.bdt("0.0000"), standardPolicy
        ))).isEmpty();

        assertThat(taxStrategy.calculateTaxLines(new TaxCalculationContext(
                UUID.randomUUID(), SupportedCurrency.BDT, Money.bdt("-25.0000"), standardPolicy
        ))).isEmpty();
    }

    @Test
    void prepaidCreditGrantEntityRejectsOverdraftDeduction() {
        Instant now = Instant.parse("2026-04-01T00:00:00Z");
        PrepaidCreditGrantEntity grant = new PrepaidCreditGrantEntity(
                UUID.randomUUID(),
                SupportedCurrency.USD,
                CreditGrantType.PREPAID,
                "Initial credit",
                new BigDecimal("25.0000"),
                100,
                null,
                "grant-1",
                now
        );

        grant.deduct(new BigDecimal("10.0000"), now);
        assertThat(grant.getRemainingAmount()).isEqualByComparingTo("15.0000");

        assertThatThrownBy(() -> grant.deduct(new BigDecimal("15.0001"), now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("overdraft");
    }

    @Test
    void invoiceEntityTransitionsAccuratelyAcrossDraftFinalizedPaidCreditNoteAndVoid() {
        Instant pStart = Instant.parse("2026-01-01T00:00:00Z");
        Instant pEnd = Instant.parse("2026-02-01T00:00:00Z");
        Instant now = Instant.parse("2026-02-01T01:00:00Z");

        InvoiceEntity invoice = new InvoiceEntity(
                UUID.randomUUID(), UUID.randomUUID(), null,
                pStart, pEnd, "Asia/Dhaka", pEnd.plusSeconds(3600),
                SupportedCurrency.BDT, "idem-inv-1", now
        );
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);

        invoice.refreshDraftTotals(
                null, pEnd.plusSeconds(7200),
                new BigDecimal("100.0050"), new BigDecimal("100.0050"),
                new BigDecimal("15.0008"), new BigDecimal("115.0058"),
                new BigDecimal("115.01"), now
        );
        assertThat(invoice.getPayableAmountDue()).isEqualByComparingTo("115.01");

        // Finalize with partial credit leaving 65.0058 due -> FINALIZED
        invoice.finalizeInvoice(
                "INV-2026-100001", new BigDecimal("50.0000"),
                new BigDecimal("65.0058"), new BigDecimal("65.01"),
                now, now.plusSeconds(86400), now
        );
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.FINALIZED);
        assertThat(invoice.getInvoiceNumber()).isEqualTo("INV-2026-100001");

        // Apply credit note clearing remaining open due -> PAID
        invoice.applyCreditNoteReduction(
                new BigDecimal("65.0058"), BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(2), now);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getAmountDue()).isEqualByComparingTo("0.0000");

        // Separate draft void clears amounts and records reason
        InvoiceEntity draftToVoid = new InvoiceEntity(
                UUID.randomUUID(), UUID.randomUUID(), null,
                pStart, pEnd, "UTC", pEnd, SupportedCurrency.USD, null, now
        );
        draftToVoid.markVoid("Duplicate test cycle", now);
        assertThat(draftToVoid.getStatus()).isEqualTo(InvoiceStatus.VOID);
        assertThat(draftToVoid.getVoidReason()).isEqualTo("Duplicate test cycle");
        assertThat(draftToVoid.getPayableAmountDue()).isEqualByComparingTo("0.00");
    }

    @Test
    void invoiceEntityMarksPaidImmediatelyWhenPrepaidCreditsCoverFullTotal() {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        InvoiceEntity invoice = new InvoiceEntity(
                UUID.randomUUID(), UUID.randomUUID(), null,
                now.minusSeconds(3600), now, "UTC", now, SupportedCurrency.USD, null, now
        );
        invoice.refreshDraftTotals(
                null, now,
                new BigDecimal("49.9900"), new BigDecimal("49.9900"),
                BigDecimal.ZERO.setScale(4), new BigDecimal("49.9900"),
                new BigDecimal("49.99"), now
        );
        invoice.finalizeInvoice(
                "INV-2026-100002", new BigDecimal("49.9900"),
                BigDecimal.ZERO.setScale(4), BigDecimal.ZERO.setScale(2),
                now, now.plusSeconds(86400), now
        );
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getCreditsAppliedAmount()).isEqualByComparingTo("49.9900");
        assertThat(invoice.getAmountDue()).isEqualByComparingTo("0.0000");
    }

    @Test
    void billingAdjustmentEntityPreservesPendingDraftReleasedAndBilledStateTransitions() {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        UUID invoiceId = UUID.randomUUID();
        BillingAdjustmentEntity adjustment = new BillingAdjustmentEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), BillingAdjustmentType.LATE_USAGE,
                "Late API calls", new BigDecimal("25.000000"), new BigDecimal("12.5000"),
                SupportedCurrency.USD, "late-1", Map.of("delta", "12.5000"), now
        );
        assertThat(adjustment.getStatus()).isEqualTo(BillingAdjustmentStatus.PENDING);

        adjustment.attachToDraft(invoiceId, now);
        assertThat(adjustment.getStatus()).isEqualTo(BillingAdjustmentStatus.APPLIED_TO_DRAFT);
        assertThat(adjustment.getAppliedInvoiceId()).isEqualTo(invoiceId);

        adjustment.releaseToPending(now);
        assertThat(adjustment.getStatus()).isEqualTo(BillingAdjustmentStatus.PENDING);
        assertThat(adjustment.getAppliedInvoiceId()).isNull();

        adjustment.markBilled(invoiceId, now);
        assertThat(adjustment.getStatus()).isEqualTo(BillingAdjustmentStatus.BILLED);
        assertThat(adjustment.getAppliedInvoiceId()).isEqualTo(invoiceId);
    }

    @Test
    void billingRunEntityHandlesFailureErrorTruncationResumeAndCompletion() {
        Instant now = Instant.parse("2026-03-01T00:00:00Z");
        BillingRunEntity run = new BillingRunEntity(
                BillingRunType.FINALIZATION, null, now, "run-key-1", now
        );
        assertThat(run.getStatus()).isEqualTo(BillingRunStatus.RUNNING);

        String longError = "X".repeat(650);
        run.fail(longError, 10, 4, 0, 1, now);
        assertThat(run.getStatus()).isEqualTo(BillingRunStatus.FAILED);
        assertThat(run.getErrorSummary()).hasSize(500);

        run.markResuming();
        assertThat(run.getStatus()).isEqualTo(BillingRunStatus.RUNNING);
        assertThat(run.getErrorSummary()).isNull();
        assertThat(run.getCompletedAt()).isNull();

        run.attachJobExecution(42L);
        run.complete(10, 6, 4, 0, now);
        assertThat(run.getStatus()).isEqualTo(BillingRunStatus.COMPLETED);
        assertThat(run.getBatchJobExecutionId()).isEqualTo(42L);
        assertThat(run.getProcessedCount()).isEqualTo(10);
        assertThat(run.getCreatedCount()).isEqualTo(6);
        assertThat(run.getSkippedCount()).isEqualTo(4);
    }
}
