package com.meterflow.notifications.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "audit_logs")
class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "actor_email", length = 255, updatable = false)
    private String actorEmail;

    @Column(name = "customer_id", updatable = false)
    private UUID customerId;

    @Column(name = "action", nullable = false, length = 80, updatable = false)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 48, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> details = new LinkedHashMap<>();

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    AuditLogEntity(
            UUID actorUserId,
            String actorEmail,
            UUID customerId,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> details,
            Instant occurredAt
    ) {
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.customerId = customerId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        this.occurredAt = occurredAt;
        this.createdAt = occurredAt;
    }

    UUID getId() {
        return id;
    }

    UUID getActorUserId() {
        return actorUserId;
    }

    String getActorEmail() {
        return actorEmail;
    }

    UUID getCustomerId() {
        return customerId;
    }

    String getAction() {
        return action;
    }

    String getResourceType() {
        return resourceType;
    }

    UUID getResourceId() {
        return resourceId;
    }

    Map<String, Object> getDetails() {
        return details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    Instant getOccurredAt() {
        return occurredAt;
    }
}
