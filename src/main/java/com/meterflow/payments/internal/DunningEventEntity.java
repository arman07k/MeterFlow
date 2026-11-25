package com.meterflow.payments.internal;

import com.meterflow.payments.api.DunningEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dunning_events")
class DunningEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "dunning_case_id", nullable = false, updatable = false)
    private UUID dunningCaseId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 36, updatable = false)
    private DunningEventType eventType;

    @Column(name = "from_status", length = 24, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 24, updatable = false)
    private String toStatus;

    @Column(name = "attempt_number", updatable = false)
    private Integer attemptNumber;

    @Column(name = "scheduled_for", updatable = false)
    private Instant scheduledFor;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> details = new LinkedHashMap<>();

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DunningEventEntity() {
    }

    DunningEventEntity(
            UUID dunningCaseId,
            UUID invoiceId,
            UUID customerId,
            UUID subscriptionId,
            DunningEventType eventType,
            String fromStatus,
            String toStatus,
            Integer attemptNumber,
            Instant scheduledFor,
            Map<String, Object> details,
            Instant occurredAt
    ) {
        this.dunningCaseId = dunningCaseId;
        this.invoiceId = invoiceId;
        this.customerId = customerId;
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.attemptNumber = attemptNumber;
        this.scheduledFor = scheduledFor;
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        this.occurredAt = occurredAt;
        this.createdAt = occurredAt;
    }

    UUID getId() {
        return id;
    }

    UUID getDunningCaseId() {
        return dunningCaseId;
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

    DunningEventType getEventType() {
        return eventType;
    }

    String getFromStatus() {
        return fromStatus;
    }

    String getToStatus() {
        return toStatus;
    }

    Integer getAttemptNumber() {
        return attemptNumber;
    }

    Instant getScheduledFor() {
        return scheduledFor;
    }

    Map<String, Object> getDetails() {
        return details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
