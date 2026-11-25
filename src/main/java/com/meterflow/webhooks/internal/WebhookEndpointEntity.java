package com.meterflow.webhooks.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webhook_endpoints")
class WebhookEndpointEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "target_url", nullable = false, length = 1000)
    private String targetUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subscribed_events", nullable = false, columnDefinition = "jsonb")
    private List<String> subscribedEvents = new ArrayList<>();

    @Column(name = "active_signing_secret_enc", nullable = false, columnDefinition = "text")
    private String activeSigningSecretEnc;

    @Column(name = "secret_prefix", nullable = false, length = 24)
    private String secretPrefix;

    @Column(name = "previous_signing_secret_enc", columnDefinition = "text")
    private String previousSigningSecretEnc;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpointEntity() {
    }

    WebhookEndpointEntity(
            UUID customerId,
            String name,
            String targetUrl,
            List<String> subscribedEvents,
            String activeSigningSecretEnc,
            String secretPrefix,
            Instant now
    ) {
        this.customerId = customerId;
        this.name = name;
        this.targetUrl = targetUrl;
        this.subscribedEvents = new ArrayList<>(subscribedEvents);
        this.activeSigningSecretEnc = activeSigningSecretEnc;
        this.secretPrefix = secretPrefix;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void updateConfiguration(String name, String targetUrl, List<String> subscribedEvents, Boolean active, Instant now) {
        if (name != null) {
            this.name = name;
        }
        if (targetUrl != null) {
            this.targetUrl = targetUrl;
        }
        if (subscribedEvents != null) {
            this.subscribedEvents = new ArrayList<>(subscribedEvents);
        }
        if (active != null) {
            this.active = active;
        }
        this.updatedAt = now;
    }

    void rotateSecret(String newEncryptedSecret, String newPrefix, Instant previousExpiresAt, Instant now) {
        this.previousSigningSecretEnc = this.activeSigningSecretEnc;
        this.previousSecretExpiresAt = previousExpiresAt;
        this.activeSigningSecretEnc = newEncryptedSecret;
        this.secretPrefix = newPrefix;
        this.updatedAt = now;
    }

    void softDelete(Instant now) {
        this.active = false;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    boolean isDeleted() {
        return deletedAt != null;
    }

    boolean isDualKeyGraceActive(Instant now) {
        return previousSigningSecretEnc != null
                && previousSecretExpiresAt != null
                && previousSecretExpiresAt.isAfter(now);
    }

    boolean subscribesTo(String eventType) {
        return subscribedEvents != null && subscribedEvents.contains(eventType);
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    String getName() {
        return name;
    }

    String getTargetUrl() {
        return targetUrl;
    }

    List<String> getSubscribedEvents() {
        return subscribedEvents == null ? List.of() : List.copyOf(subscribedEvents);
    }

    String getActiveSigningSecretEnc() {
        return activeSigningSecretEnc;
    }

    String getSecretPrefix() {
        return secretPrefix;
    }

    String getPreviousSigningSecretEnc() {
        return previousSigningSecretEnc;
    }

    Instant getPreviousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    boolean isActive() {
        return active;
    }

    Instant getDeletedAt() {
        return deletedAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
