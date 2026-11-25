package com.meterflow.billing.internal;

import com.meterflow.billing.api.CreditGrantType;
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
@Table(name = "prepaid_credit_grants")
class PrepaidCreditGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "grant_type", nullable = false, length = 24, updatable = false)
    private CreditGrantType grantType;

    @Column(name = "description", nullable = false, length = 255, updatable = false)
    private String description;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal originalAmount;

    @Column(name = "remaining_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal remainingAmount;

    @Column(name = "priority", nullable = false, updatable = false)
    private int priority;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PrepaidCreditGrantEntity() {
    }

    PrepaidCreditGrantEntity(
            UUID customerId,
            SupportedCurrency currency,
            CreditGrantType grantType,
            String description,
            BigDecimal amount,
            int priority,
            Instant expiresAt,
            String idempotencyKey,
            Instant now
    ) {
        this.customerId = customerId;
        this.currency = currency;
        this.grantType = grantType;
        this.description = description;
        this.originalAmount = amount;
        this.remainingAmount = amount;
        this.priority = priority;
        this.expiresAt = expiresAt;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    CreditGrantType getGrantType() {
        return grantType;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    int getPriority() {
        return priority;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void deduct(BigDecimal deduction, Instant now) {
        BigDecimal next = this.remainingAmount.subtract(deduction);
        if (next.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Credit grant overdraft prevented");
        }
        this.remainingAmount = next;
        this.updatedAt = now;
    }
}
