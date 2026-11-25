package com.meterflow.billing.internal;

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
@Table(name = "credit_note_lines")
class CreditNoteLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "credit_note_id", nullable = false, updatable = false)
    private UUID creditNoteId;

    @Column(name = "original_invoice_line_id", updatable = false)
    private UUID originalInvoiceLineId;

    @Column(name = "description", nullable = false, length = 255, updatable = false)
    private String description;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal creditAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditNoteLineEntity() {
    }

    CreditNoteLineEntity(
            UUID creditNoteId,
            UUID originalInvoiceLineId,
            String description,
            BigDecimal creditAmount,
            BigDecimal taxAmount,
            SupportedCurrency currency,
            Instant now
    ) {
        this.creditNoteId = creditNoteId;
        this.originalInvoiceLineId = originalInvoiceLineId;
        this.description = description;
        this.creditAmount = creditAmount;
        this.taxAmount = taxAmount;
        this.currency = currency;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCreditNoteId() {
        return creditNoteId;
    }

    UUID getOriginalInvoiceLineId() {
        return originalInvoiceLineId;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getCreditAmount() {
        return creditAmount;
    }

    BigDecimal getTaxAmount() {
        return taxAmount;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
