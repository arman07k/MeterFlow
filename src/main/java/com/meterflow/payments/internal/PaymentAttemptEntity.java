package com.meterflow.payments.internal;

import com.meterflow.payments.api.MockPaymentOutcome;
import com.meterflow.payments.api.PaymentAttemptSource;
import com.meterflow.payments.api.PaymentAttemptStatus;
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
@Table(name = "payment_attempts")
class PaymentAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "payment_transaction_id", nullable = false, updatable = false)
    private UUID paymentTransactionId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "dunning_case_id", updatable = false)
    private UUID dunningCaseId;

    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "attempt_source", nullable = false, length = 24, updatable = false)
    private PaymentAttemptSource attemptSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "simulation_outcome", nullable = false, length = 32, updatable = false)
    private MockPaymentOutcome simulationOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24, updatable = false)
    private PaymentAttemptStatus status;

    @Column(name = "retryable", nullable = false, updatable = false)
    private boolean retryable;

    @Column(name = "failure_code", length = 64, updatable = false)
    private String failureCode;

    @Column(name = "failure_reason", length = 255, updatable = false)
    private String failureReason;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private SupportedCurrency currency;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentAttemptEntity() {
    }

    PaymentAttemptEntity(
            UUID paymentTransactionId,
            UUID invoiceId,
            UUID customerId,
            UUID dunningCaseId,
            int attemptNumber,
            PaymentAttemptSource attemptSource,
            MockPaymentOutcome simulationOutcome,
            PaymentAttemptStatus status,
            boolean retryable,
            String failureCode,
            String failureReason,
            BigDecimal amount,
            SupportedCurrency currency,
            String idempotencyKey,
            Instant attemptedAt
    ) {
        this.paymentTransactionId = paymentTransactionId;
        this.invoiceId = invoiceId;
        this.customerId = customerId;
        this.dunningCaseId = dunningCaseId;
        this.attemptNumber = attemptNumber;
        this.attemptSource = attemptSource;
        this.simulationOutcome = simulationOutcome;
        this.status = status;
        this.retryable = retryable;
        this.failureCode = failureCode;
        this.failureReason = failureReason;
        this.amount = amount;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.attemptedAt = attemptedAt;
        this.createdAt = attemptedAt;
    }

    UUID getId() {
        return id;
    }

    UUID getPaymentTransactionId() {
        return paymentTransactionId;
    }

    UUID getInvoiceId() {
        return invoiceId;
    }

    UUID getCustomerId() {
        return customerId;
    }

    UUID getDunningCaseId() {
        return dunningCaseId;
    }

    int getAttemptNumber() {
        return attemptNumber;
    }

    PaymentAttemptSource getAttemptSource() {
        return attemptSource;
    }

    MockPaymentOutcome getSimulationOutcome() {
        return simulationOutcome;
    }

    PaymentAttemptStatus getStatus() {
        return status;
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

    BigDecimal getAmount() {
        return amount;
    }

    SupportedCurrency getCurrency() {
        return currency;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    Instant getAttemptedAt() {
        return attemptedAt;
    }
}
