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
@Table(name = "invoice_tax_lines")
class InvoiceTaxLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "tax_policy_id", updatable = false)
    private UUID taxPolicyId;

    @Column(name = "tax_name", nullable = false, length = 100, updatable = false)
    private String taxName;

    @Column(name = "jurisdiction", nullable = false, length = 80, updatable = false)
    private String jurisdiction;

    @Column(name = "taxable_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxableAmount;

    @Column(name = "rate_percent", nullable = false, precision = 7, scale = 4, updatable = false)
    private BigDecimal ratePercent;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InvoiceTaxLineEntity() {
    }

    InvoiceTaxLineEntity(
            UUID invoiceId,
            UUID taxPolicyId,
            String taxName,
            String jurisdiction,
            BigDecimal taxableAmount,
            BigDecimal ratePercent,
            BigDecimal taxAmount,
            SupportedCurrency currency,
            Instant now
    ) {
        this.invoiceId = invoiceId;
        this.taxPolicyId = taxPolicyId;
        this.taxName = taxName;
        this.jurisdiction = jurisdiction;
        this.taxableAmount = taxableAmount;
        this.ratePercent = ratePercent;
        this.taxAmount = taxAmount;
        this.currency = currency;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    UUID getTaxPolicyId() {
        return taxPolicyId;
    }

    String getTaxName() {
        return taxName;
    }

    String getJurisdiction() {
        return jurisdiction;
    }

    BigDecimal getTaxableAmount() {
        return taxableAmount;
    }

    BigDecimal getRatePercent() {
        return ratePercent;
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
