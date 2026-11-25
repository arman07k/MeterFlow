package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingAdjustmentStatus;
import com.meterflow.billing.api.BillingAdjustmentType;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "billing_adjustments")
class BillingAdjustmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "original_invoice_id", updatable = false)
    private UUID originalInvoiceId;

    @Column(name = "applied_invoice_id")
    private UUID appliedInvoiceId;

    @Column(name = "late_usage_record_id", updatable = false)
    private UUID lateUsageRecordId;

    @Column(name = "metric_id", updatable = false)
    private UUID metricId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 32, updatable = false)
    private BillingAdjustmentType adjustmentType;

    @Column(name = "description", nullable = false, length = 255, updatable = false)
    private String description;

    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal quantityDelta;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private BillingAdjustmentStatus status;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_breakdown", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> calculationBreakdown = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillingAdjustmentEntity() {
    }

    BillingAdjustmentEntity(
            UUID customerId,
            UUID subscriptionId,
            UUID originalInvoiceId,
            UUID lateUsageRecordId,
            UUID metricId,
            BillingAdjustmentType adjustmentType,
            String description,
            BigDecimal quantityDelta,
            BigDecimal amount,
            SupportedCurrency currency,
            String idempotencyKey,
            Map<String, Object> calculationBreakdown,
            Instant now
    ) {
        this.customerId = customerId;
        this.subscriptionId = subscriptionId;
        this.originalInvoiceId = originalInvoiceId;
        this.lateUsageRecordId = lateUsageRecordId;
        this.metricId = metricId;
        this.adjustmentType = adjustmentType;
        this.description = description;
        this.quantityDelta = quantityDelta;
        this.amount = amount;
        this.currency = currency;
        this.status = BillingAdjustmentStatus.PENDING;
        this.idempotencyKey = idempotencyKey;
        this.calculationBreakdown = calculationBreakdown == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(calculationBreakdown);
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getOriginalInvoiceId() {
        return originalInvoiceId;
    }

    UUID getAppliedInvoiceId() {
        return appliedInvoiceId;
    }

    UUID getLateUsageRecordId() {
        return lateUsageRecordId;
    }

    UUID getMetricId() {
        return metricId;
    }

    BillingAdjustmentType getAdjustmentType() {
        return adjustmentType;
    }

    String getDescription() {
        return description;
    }

    BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    BigDecimal getAmount() {
        return amount;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    BillingAdjustmentStatus getStatus() {
        return status;
    }

    Map<String, Object> getCalculationBreakdown() {
        return calculationBreakdown == null ? Map.of() : new LinkedHashMap<>(calculationBreakdown);
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void attachToDraft(UUID invoiceId, Instant now) {
        this.appliedInvoiceId = invoiceId;
        this.status = BillingAdjustmentStatus.APPLIED_TO_DRAFT;
        this.updatedAt = now;
    }

    void releaseToPending(Instant now) {
        this.appliedInvoiceId = null;
        this.status = BillingAdjustmentStatus.PENDING;
        this.updatedAt = now;
    }

    void markBilled(UUID invoiceId, Instant now) {
        this.appliedInvoiceId = invoiceId;
        this.status = BillingAdjustmentStatus.BILLED;
        this.updatedAt = now;
    }
}
