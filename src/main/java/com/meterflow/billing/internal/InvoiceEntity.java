package com.meterflow.billing.internal;

import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.shared.SupportedCurrency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invoices")
class InvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_number", length = 50)
    private String invoiceNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "billing_run_id")
    private UUID billingRunId;

    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private Instant periodEnd;

    @Column(name = "billing_timezone", nullable = false, length = 64, updatable = false)
    private String billingTimezone;

    @Column(name = "grace_expires_at", nullable = false)
    private Instant graceExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "subtotal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotalAmount;

    @Column(name = "taxable_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "credits_applied_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditsAppliedAmount;

    @Column(name = "credit_notes_applied_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditNotesAppliedAmount;

    @Column(name = "amount_due", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountDue;

    @Column(name = "payable_amount_due", nullable = false, precision = 19, scale = 2)
    private BigDecimal payableAmountDue;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private InvoiceStatus status;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvoiceEntity() {
    }

    InvoiceEntity(
            UUID customerId,
            UUID subscriptionId,
            UUID billingRunId,
            Instant periodStart,
            Instant periodEnd,
            String billingTimezone,
            Instant graceExpiresAt,
            SupportedCurrency currency,
            String idempotencyKey,
            Instant now
    ) {
        this.customerId = customerId;
        this.subscriptionId = subscriptionId;
        this.billingRunId = billingRunId;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.billingTimezone = billingTimezone;
        this.graceExpiresAt = graceExpiresAt;
        this.currency = currency;
        this.subtotalAmount = BigDecimal.ZERO.setScale(4);
        this.taxableAmount = BigDecimal.ZERO.setScale(4);
        this.taxAmount = BigDecimal.ZERO.setScale(4);
        this.totalAmount = BigDecimal.ZERO.setScale(4);
        this.creditsAppliedAmount = BigDecimal.ZERO.setScale(4);
        this.creditNotesAppliedAmount = BigDecimal.ZERO.setScale(4);
        this.amountDue = BigDecimal.ZERO.setScale(4);
        this.payableAmountDue = BigDecimal.ZERO.setScale(2);
        this.status = InvoiceStatus.DRAFT;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getInvoiceNumber() {
        return invoiceNumber;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getBillingRunId() {
        return billingRunId;
    }

    Instant getPeriodStart() {
        return periodStart;
    }

    Instant getPeriodEnd() {
        return periodEnd;
    }

    String getBillingTimezone() {
        return billingTimezone;
    }

    Instant getGraceExpiresAt() {
        return graceExpiresAt;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    BigDecimal getSubtotalAmount() {
        return subtotalAmount;
    }

    BigDecimal getTaxableAmount() {
        return taxableAmount;
    }

    BigDecimal getTaxAmount() {
        return taxAmount;
    }

    BigDecimal getTotalAmount() {
        return totalAmount;
    }

    BigDecimal getCreditsAppliedAmount() {
        return creditsAppliedAmount;
    }

    BigDecimal getCreditNotesAppliedAmount() {
        return creditNotesAppliedAmount;
    }

    BigDecimal getAmountDue() {
        return amountDue;
    }

    BigDecimal getPayableAmountDue() {
        return payableAmountDue;
    }

    InvoiceStatus getStatus() {
        return status;
    }

    Instant getFinalizedAt() {
        return finalizedAt;
    }

    Instant getDueAt() {
        return dueAt;
    }

    Instant getVoidedAt() {
        return voidedAt;
    }

    String getVoidReason() {
        return voidReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void refreshDraftTotals(
            UUID billingRunId,
            Instant graceExpiresAt,
            BigDecimal subtotal,
            BigDecimal taxable,
            BigDecimal tax,
            BigDecimal total,
            BigDecimal payableDue,
            Instant now
    ) {
        if (billingRunId != null) {
            this.billingRunId = billingRunId;
        }
        this.graceExpiresAt = graceExpiresAt;
        this.subtotalAmount = subtotal;
        this.taxableAmount = taxable;
        this.taxAmount = tax;
        this.totalAmount = total;
        this.amountDue = total.max(BigDecimal.ZERO.setScale(4));
        this.payableAmountDue = payableDue;
        this.updatedAt = now;
    }

    void finalizeInvoice(
            String invoiceNumber,
            BigDecimal creditsApplied,
            BigDecimal remainingDue,
            BigDecimal payableDue,
            Instant finalizedAt,
            Instant dueAt,
            Instant now
    ) {
        this.invoiceNumber = invoiceNumber;
        this.creditsAppliedAmount = creditsApplied;
        this.amountDue = remainingDue;
        this.payableAmountDue = payableDue;
        this.status = remainingDue.compareTo(BigDecimal.ZERO) == 0 ? InvoiceStatus.PAID : InvoiceStatus.FINALIZED;
        this.finalizedAt = finalizedAt;
        this.dueAt = dueAt;
        this.updatedAt = now;
    }

    void applyCreditNoteReduction(
            BigDecimal addedCreditNoteAmount,
            BigDecimal newAmountDue,
            BigDecimal newPayableDue,
            Instant now
    ) {
        this.creditNotesAppliedAmount = this.creditNotesAppliedAmount.add(addedCreditNoteAmount);
        this.amountDue = newAmountDue;
        this.payableAmountDue = newPayableDue;
        if (this.status == InvoiceStatus.FINALIZED && newAmountDue.compareTo(BigDecimal.ZERO) == 0) {
            this.status = InvoiceStatus.PAID;
        }
        this.updatedAt = now;
    }

    void markVoid(String reason, Instant now) {
        this.status = InvoiceStatus.VOID;
        this.amountDue = BigDecimal.ZERO.setScale(4);
        this.payableAmountDue = BigDecimal.ZERO.setScale(2);
        this.voidedAt = now;
        this.voidReason = reason;
        this.updatedAt = now;
    }

    void recordPaymentSettlement(BigDecimal newAmountDue, BigDecimal newPayableDue, Instant now) {
        this.amountDue = newAmountDue;
        this.payableAmountDue = newPayableDue;
        if (newAmountDue.compareTo(BigDecimal.ZERO) == 0) {
            this.status = InvoiceStatus.PAID;
        }
        this.updatedAt = now;
    }

    void markPastDue(Instant now) {
        this.status = InvoiceStatus.PAST_DUE;
        this.updatedAt = now;
    }
}
