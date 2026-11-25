package com.meterflow.notifications.api;

import java.time.Instant;
import java.util.UUID;

public record NotificationRecord(
        UUID id,
        UUID customerId,
        NotificationType type,
        String title,
        String message,
        String resourceType,
        UUID resourceId,
        UUID sourceEventId,
        boolean read,
        Instant readAt,
        Instant createdAt
) {
}
