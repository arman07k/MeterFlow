package com.meterflow.billing.internal;

import com.meterflow.billing.api.CreditNoteType;
import com.meterflow.shared.SupportedCurrency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credit_notes")
class CreditNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "credit_note_number", nullable = false, length = 50, updatable = false)
    private String creditNoteNumber;

    @Column(name = "original_invoice_id", nullable = false, updatable = false)
    private UUID originalInvoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_type", nullable = false, length = 24, updatable = false)
    private CreditNoteType creditType;

    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "subtotal_credit", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal subtotalCredit;

    @Column(name = "tax_credit", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxCredit;

    @Column(name = "total_credit", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal totalCredit;

    @Column(name = "applied_to_invoice_due", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal appliedToInvoiceDue;

    @Column(name = "refunded_to_credit_wallet", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal refundedToCreditWallet;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditNoteEntity() {
    }

    CreditNoteEntity(
            String creditNoteNumber,
            UUID originalInvoiceId,
            UUID customerId,
            SupportedCurrency currency,
            CreditNoteType creditType,
            String reason,
            BigDecimal subtotalCredit,
            BigDecimal taxCredit,
            BigDecimal totalCredit,
            BigDecimal appliedToInvoiceDue,
            BigDecimal refundedToCreditWallet,
            String idempotencyKey,
            Instant now
    ) {
        this.creditNoteNumber = creditNoteNumber;
        this.originalInvoiceId = originalInvoiceId;
        this.customerId = customerId;
        this.currency = currency;
        this.creditType = creditType;
        this.reason = reason;
        this.subtotalCredit = subtotalCredit;
        this.taxCredit = taxCredit;
        this.totalCredit = totalCredit;
        this.appliedToInvoiceDue = appliedToInvoiceDue;
        this.refundedToCreditWallet = refundedToCreditWallet;
        this.idempotencyKey = idempotencyKey;
        this.issuedAt = now;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    String getCreditNoteNumber() {
        return creditNoteNumber;
    }

    UUID getOriginalInvoiceId() {
        return originalInvoiceId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    CreditNoteType getCreditType() {
        return creditType;
    }

    String getReason() {
        return reason;
    }

    BigDecimal getSubtotalCredit() {
        return subtotalCredit;
    }

    BigDecimal getTaxCredit() {
        return taxCredit;
    }

    BigDecimal getTotalCredit() {
        return totalCredit;
    }

    BigDecimal getAppliedToInvoiceDue() {
        return appliedToInvoiceDue;
    }

    BigDecimal getRefundedToCreditWallet() {
        return refundedToCreditWallet;
    }

    Instant getIssuedAt() {
        return issuedAt;
    }
}
