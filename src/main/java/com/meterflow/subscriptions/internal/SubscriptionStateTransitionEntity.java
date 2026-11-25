package com.meterflow.subscriptions.internal;

import com.meterflow.subscriptions.api.SubscriptionStatus;
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
@Table(name = "subscription_state_transitions")
class SubscriptionStateTransitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20, updatable = false)
    private SubscriptionStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private SubscriptionStatus toStatus;

    @Column(name = "reason", nullable = false, length = 80, updatable = false)
    private String reason;

    @Column(name = "effective_at", nullable = false, updatable = false)
    private Instant effectiveAt;

    @Column(name = "idempotency_key", length = 150, updatable = false)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionStateTransitionEntity() {
    }

    SubscriptionStateTransitionEntity(
            UUID subscriptionId,
            SubscriptionStatus fromStatus,
            SubscriptionStatus toStatus,
            String reason,
            Instant effectiveAt,
            String idempotencyKey,
            Map<String, Object> metadata,
            Instant now
    ) {
        this.subscriptionId = subscriptionId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.effectiveAt = effectiveAt;
        this.idempotencyKey = idempotencyKey;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        this.createdAt = now;
    }

    UUID getId() {
        return id;
    }

    UUID getSubscriptionId() {
        return subscriptionId;
    }

    SubscriptionStatus getFromStatus() {
        return fromStatus;
    }

    SubscriptionStatus getToStatus() {
        return toStatus;
    }

    String getReason() {
        return reason;
    }

    Instant getEffectiveAt() {
        return effectiveAt;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    Map<String, Object> getMetadata() {
        return metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
