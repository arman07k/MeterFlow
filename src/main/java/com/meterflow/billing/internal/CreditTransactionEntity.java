package com.meterflow.billing.internal;

import com.meterflow.billing.api.CreditTransactionType;
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
@Table(name = "credit_transactions")
class CreditTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "credit_grant_id", nullable = false, updatable = false)
    private UUID creditGrantId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "invoice_id", updatable = false)
    private UUID invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 24, updatable = false)
    private CreditTransactionType transactionType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CreditTransactionEntity() {
    }

    CreditTransactionEntity(
            UUID creditGrantId,
            UUID customerId,
            UUID invoiceId,
            CreditTransactionType transactionType,
            BigDecimal amount,
            BigDecimal balanceAfter,
            SupportedCurrency currency,
            String reason,
            String idempotencyKey,
            Instant now
    ) {
        this.creditGrantId = creditGrantId;
        this.customerId = customerId;
        this.invoiceId = invoiceId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.currency = currency;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCreditGrantId() {
        return creditGrantId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    CreditTransactionType getTransactionType() {
        return transactionType;
    }

    BigDecimal getAmount() {
        return amount;
    }

    BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    String getReason() {
        return reason;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
