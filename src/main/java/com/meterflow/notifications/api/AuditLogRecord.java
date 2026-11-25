package com.meterflow.notifications.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditLogRecord(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        UUID customerId,
        String action,
        String resourceType,
        UUID resourceId,
        Map<String, Object> details,
        Instant occurredAt
) {
}
