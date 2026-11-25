package com.meterflow.billing.internal;

import com.meterflow.billing.api.InvoiceLineType;
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
@Table(name = "invoice_lines")
class InvoiceLineEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "line_order", nullable = false)
    private int lineOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, length = 32, updatable = false)
    private InvoiceLineType lineType;

    @Column(name = "description", nullable = false, length = 255, updatable = false)
    private String description;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "plan_version_id", updatable = false)
    private UUID planVersionId;

    @Column(name = "charge_id", updatable = false)
    private UUID chargeId;

    @Column(name = "charge_code", length = 80, updatable = false)
    private String chargeCode;

    @Column(name = "charge_type", length = 40, updatable = false)
    private String chargeType;

    @Column(name = "metric_id", updatable = false)
    private UUID metricId;

    @Column(name = "window_start", nullable = false, updatable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false, updatable = false)
    private Instant windowEnd;

    @Column(name = "seat_quantity", updatable = false)
    private Integer seatQuantity;

    @Column(name = "raw_quantity", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal rawQuantity;

    @Column(name = "free_allowance_quantity", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal freeAllowanceQuantity;

    @Column(name = "billable_quantity", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal billableQuantity;

    @Column(name = "unit_price", precision = 19, scale = 8, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal lineAmount;

    @Column(name = "taxable", nullable = false, updatable = false)
    private boolean taxable;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "source_reference_id", updatable = false)
    private UUID sourceReferenceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation_breakdown", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> calculationBreakdown = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InvoiceLineEntity() {
    }

    InvoiceLineEntity(
            UUID invoiceId,
            int lineOrder,
            InvoiceLineType lineType,
            String description,
            UUID subscriptionId,
            UUID planVersionId,
            UUID chargeId,
            String chargeCode,
            String chargeType,
            UUID metricId,
            Instant windowStart,
            Instant windowEnd,
            Integer seatQuantity,
            BigDecimal rawQuantity,
            BigDecimal freeAllowanceQuantity,
            BigDecimal billableQuantity,
            BigDecimal unitPrice,
            BigDecimal lineAmount,
            boolean taxable,
            SupportedCurrency currency,
            UUID sourceReferenceId,
            Map<String, Object> calculationBreakdown,
            Instant now
    ) {
        this.invoiceId = invoiceId;
        this.lineOrder = lineOrder;
        this.lineType = lineType;
        this.description = description;
        this.subscriptionId = subscriptionId;
        this.planVersionId = planVersionId;
        this.chargeId = chargeId;
        this.chargeCode = chargeCode;
        this.chargeType = chargeType;
        this.metricId = metricId;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.seatQuantity = seatQuantity;
        this.rawQuantity = rawQuantity;
        this.freeAllowanceQuantity = freeAllowanceQuantity;
        this.billableQuantity = billableQuantity;
        this.unitPrice = unitPrice;
        this.lineAmount = lineAmount;
        this.taxable = taxable;
        this.currency = currency;
        this.sourceReferenceId = sourceReferenceId;
        this.calculationBreakdown = calculationBreakdown == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(calculationBreakdown);
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    int getLineOrder() {
        return lineOrder;
    }

    InvoiceLineType getLineType() {
        return lineType;
    }

    String getDescription() {
        return description;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getPlanVersionId() {
        return planVersionId;
    }

    UUID getChargeId() {
        return chargeId;
    }

    String getChargeCode() {
        return chargeCode;
    }

    String getChargeType() {
        return chargeType;
    }

    UUID getMetricId() {
        return metricId;
    }

    Instant getWindowStart() {
        return windowStart;
    }

    Instant getWindowEnd() {
        return windowEnd;
    }

    Integer getSeatQuantity() {
        return seatQuantity;
    }

    BigDecimal getRawQuantity() {
        return rawQuantity;
    }

    BigDecimal getFreeAllowanceQuantity() {
        return freeAllowanceQuantity;
    }

    BigDecimal getBillableQuantity() {
        return billableQuantity;
    }

    BigDecimal getUnitPrice() {
        return unitPrice;
    }

    BigDecimal getLineAmount() {
        return lineAmount;
    }

    boolean isTaxable() {
        return taxable;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    UUID getSourceReferenceId() {
        return sourceReferenceId;
    }

    Map<String, Object> getCalculationBreakdown() {
        return calculationBreakdown == null ? Map.of() : new LinkedHashMap<>(calculationBreakdown);
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
