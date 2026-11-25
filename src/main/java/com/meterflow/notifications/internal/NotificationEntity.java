package com.meterflow.notifications.internal;

import com.meterflow.notifications.api.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 48, updatable = false)
    private NotificationType notificationType;

    @Column(name = "title", nullable = false, length = 160, updatable = false)
    private String title;

    @Column(name = "message", nullable = false, length = 500, updatable = false)
    private String message;

    @Column(name = "resource_type", length = 48, updatable = false)
    private String resourceType;

    @Column(name = "resource_id", updatable = false)
    private UUID resourceId;

    @Column(name = "source_event_id", updatable = false)
    private UUID sourceEventId;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationEntity() {
    }

    NotificationEntity(
            UUID customerId,
            NotificationType notificationType,
            String title,
            String message,
            String resourceType,
            UUID resourceId,
            UUID sourceEventId,
            Instant createdAt
    ) {
        this.customerId = customerId;
        this.notificationType = notificationType;
        this.title = truncate(title, 160);
        this.message = truncate(message, 500);
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.sourceEventId = sourceEventId;
        this.read = false;
        this.createdAt = createdAt;
    }

    void markRead(Instant now) {
        if (!this.read) {
            this.read = true;
            this.readAt = now;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    UUID getId() {
        return id;
    }

    UUID getCustomerId() {
        return customerId;
    }

    NotificationType getNotificationType() {
        return notificationType;
    }

    String getTitle() {
        return title;
    }

    String getMessage() {
        return message;
    }

    String getResourceType() {
        return resourceType;
    }

    UUID getResourceId() {
        return resourceId;
    }

    UUID getSourceEventId() {
        return sourceEventId;
    }

    boolean isRead() {
        return read;
    }

    Instant getReadAt() {
        return readAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
