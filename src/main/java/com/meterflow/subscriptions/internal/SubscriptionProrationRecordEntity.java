package com.meterflow.subscriptions.internal;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "subscription_proration_records")
class SubscriptionProrationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "previous_plan_version_id", nullable = false, updatable = false)
    private UUID previousPlanVersionId;

    @Column(name = "new_plan_version_id", nullable = false, updatable = false)
    private UUID newPlanVersionId;

    @Column(name = "previous_seat_quantity", nullable = false, updatable = false)
    private int previousSeatQuantity;

    @Column(name = "new_seat_quantity", nullable = false, updatable = false)
    private int newSeatQuantity;

    @Column(name = "period_start", nullable = false, updatable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false, updatable = false)
    private Instant periodEnd;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Column(name = "total_period_seconds", nullable = false, updatable = false)
    private long totalPeriodSeconds;

    @Column(name = "remaining_seconds", nullable = false, updatable = false)
    private long remainingSeconds;

    @Column(name = "proration_factor", nullable = false, precision = 19, scale = 8, updatable = false)
    private BigDecimal prorationFactor;

    @Column(name = "previous_plan_recurring_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal previousPlanRecurringAmount;

    @Column(name = "new_plan_recurring_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal newPlanRecurringAmount;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal creditAmount;

    @Column(name = "debit_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal debitAmount;

    @Column(name = "net_proration_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal netProrationAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "non_proratable_usage_charges", nullable = false, columnDefinition = "jsonb", updatable = false)
    private List<String> nonProratableUsageCharges = new ArrayList<>();

    @Column(name = "calculation_policy", nullable = false, length = 40, updatable = false)
    private String calculationPolicy;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionProrationRecordEntity() {
    }

    SubscriptionProrationRecordEntity(
            UUID subscriptionId,
            UUID previousPlanVersionId,
            UUID newPlanVersionId,
            int previousSeatQuantity,
            int newSeatQuantity,
            Instant periodStart,
            Instant periodEnd,
            Instant effectiveAt,
            long totalPeriodSeconds,
            long remainingSeconds,
            BigDecimal prorationFactor,
            BigDecimal previousPlanRecurringAmount,
            BigDecimal newPlanRecurringAmount,
            BigDecimal creditAmount,
            BigDecimal debitAmount,
            BigDecimal netProrationAmount,
            SupportedCurrency currency,
            List<String> nonProratableUsageCharges,
            String calculationPolicy,
            String idempotencyKey,
            Instant now
    ) {
        this.subscriptionId = subscriptionId;
        this.previousPlanVersionId = previousPlanVersionId;
        this.newPlanVersionId = newPlanVersionId;
        this.previousSeatQuantity = previousSeatQuantity;
        this.newSeatQuantity = newSeatQuantity;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.effectiveAt = effectiveAt;
        this.totalPeriodSeconds = totalPeriodSeconds;
        this.remainingSeconds = remainingSeconds;
        this.prorationFactor = prorationFactor;
        this.previousPlanRecurringAmount = previousPlanRecurringAmount;
        this.newPlanRecurringAmount = newPlanRecurringAmount;
        this.creditAmount = creditAmount;
        this.debitAmount = debitAmount;
        this.netProrationAmount = netProrationAmount;
        this.currency = currency;
        this.nonProratableUsageCharges = nonProratableUsageCharges == null
                ? new ArrayList<>()
                : new ArrayList<>(nonProratableUsageCharges);
        this.calculationPolicy = calculationPolicy;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getPreviousPlanVersionId() {
        return previousPlanVersionId;
    }

    UUID getNewPlanVersionId() {
        return newPlanVersionId;
    }

    int getPreviousSeatQuantity() {
        return previousSeatQuantity;
    }

    int getNewSeatQuantity() {
        return newSeatQuantity;
    }

    Instant getPeriodStart() {
        return periodStart;
    }

    Instant getPeriodEnd() {
        return periodEnd;
    }

    Instant getEffectiveAt() {
        return effectiveAt;
    }

    long getTotalPeriodSeconds() {
        return totalPeriodSeconds;
    }

    long getRemainingSeconds() {
        return remainingSeconds;
    }

    BigDecimal getProrationFactor() {
        return prorationFactor;
    }

    BigDecimal getPreviousPlanRecurringAmount() {
        return previousPlanRecurringAmount;
    }

    BigDecimal getNewPlanRecurringAmount() {
        return newPlanRecurringAmount;
    }

    BigDecimal getCreditAmount() {
        return creditAmount;
    }

    BigDecimal getDebitAmount() {
        return debitAmount;
    }

    BigDecimal getNetProrationAmount() {
        return netProrationAmount;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    List<String> getNonProratableUsageCharges() {
        return nonProratableUsageCharges == null ? List.of() : List.copyOf(nonProratableUsageCharges);
    }

    String getCalculationPolicy() {
        return calculationPolicy;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
