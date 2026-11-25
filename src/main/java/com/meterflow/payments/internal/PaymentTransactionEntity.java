package com.meterflow.payments.internal;

import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentAttemptSource;
import com.meterflow.payments.api.PaymentStatus;
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
@Table(name = "payment_transactions")
class PaymentTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_reference", nullable = false, length = 50, updatable = false)
    private String paymentReference;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "dunning_case_id", updatable = false)
    private UUID dunningCaseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24, updatable = false)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_source", nullable = false, length = 24, updatable = false)
    private PaymentAttemptSource attemptSource;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "simulation_outcome", nullable = false, length = 32, updatable = false)
    private MockPaymentOutcome simulationOutcome;

    @Column(name = "retryable", nullable = false, updatable = false)
    private boolean retryable;

    @Column(name = "failure_code", length = 64, updatable = false)
    private String failureCode;

    @Column(name = "failure_reason", length = 255, updatable = false)
    private String failureReason;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "completed_at", updatable = false)
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentTransactionEntity() {
    }

    PaymentTransactionEntity(
            String paymentReference,
            UUID invoiceId,
            UUID customerId,
            UUID subscriptionId,
            UUID dunningCaseId,
            SupportedCurrency currency,
            BigDecimal amount,
            PaymentStatus status,
            PaymentAttemptSource attemptSource,
            int attemptNumber,
            MockPaymentOutcome simulationOutcome,
            boolean retryable,
            String failureCode,
            String failureReason,
            String idempotencyKey,
            Instant completedAt,
            Instant now
    ) {
        this.paymentReference = paymentReference;
        this.invoiceId = invoiceId;
        this.customerId = customerId;
        this.subscriptionId = subscriptionId;
        this.dunningCaseId = dunningCaseId;
        this.currency = currency;
        this.amount = amount;
        this.status = status;
        this.attemptSource = attemptSource;
        this.attemptNumber = attemptNumber;
        this.simulationOutcome = simulationOutcome;
        this.retryable = retryable;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.idempotencyKey = idempotencyKey;
        this.completedAt = completedAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    UUID getId() {
        return id;
    }

    String getPaymentReference() {
        return paymentReference;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    UUID getDunningCaseId() {
        return dunningCaseId;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    BigDecimal getAmount() {
        return amount;
    }

    PaymentStatus getStatus() {
        return status;
    }

    PaymentAttemptSource getAttemptSource() {
        return attemptSource;
    }

    int getAttemptNumber() {
        return attemptNumber;
    }

    MockPaymentOutcome getSimulationOutcome() {
        return simulationOutcome;
    }

    boolean isRetryable() {
        return retryable;
    }

    String getFailureCode() {
        return failureCode;
    }

    String getFailureReason() {
        return failureReason;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    Instant getCompletedAt() {
        return completedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
