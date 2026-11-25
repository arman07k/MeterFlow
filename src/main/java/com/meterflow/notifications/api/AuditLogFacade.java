package com.meterflow.notifications.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLogFacade {

    AuditLogRecord recordAudit(
            UUID actorUserId,
            String actorEmail,
            UUID customerId,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> details
    );

    Page<AuditLogRecord> listAuditLogs(UUID customerId, Pageable pageable);
}
