package com.meterflow.notifications.internal;

import com.meterflow.identity.api.CustomerAccessGuard;
import com.meterflow.identity.api.PlatformRole;
import com.meterflow.notifications.api.AuditLogFacade;
import com.meterflow.notifications.api.AuditLogRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AuditLogService implements AuditLogFacade {

    private final AuditLogRepository auditLogRepository;
    private final CustomerAccessGuard accessGuard;
    private final Clock clock;

    AuditLogService(
            AuditLogRepository auditLogRepository,
            CustomerAccessGuard accessGuard,
            Clock clock
    ) {
        this.auditLogRepository = auditLogRepository;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AuditLogRecord recordAudit(
            UUID actorUserId,
            String actorEmail,
            UUID customerId,
            String action,
            String resourceType,
            UUID resourceId,
            Map<String, Object> details
    ) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AuditLogEntity saved = auditLogRepository.saveAndFlush(new AuditLogEntity(
                actorUserId,
                actorEmail,
                customerId,
                action,
                resourceType,
                resourceId,
                details,
                now
        ));
        return toRecord(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogRecord> listAuditLogs(UUID customerId, Pageable pageable) {
        accessGuard.requireAnyRole(PlatformRole.ADMIN, PlatformRole.OPERATOR);
        Page<AuditLogEntity> page = (customerId == null)
                ? auditLogRepository.findAllByOrderByOccurredAtDesc(pageable)
                : auditLogRepository.findByCustomerIdOrderByOccurredAtDesc(customerId, pageable);
        return page.map(AuditLogService::toRecord);
    }

    static AuditLogRecord toRecord(AuditLogEntity entity) {
        return new AuditLogRecord(
                entity.getId(),
                entity.getActorUserId(),
                entity.getActorEmail(),
                entity.getCustomerId(),
                entity.getAction(),
                entity.getResourceType(),
                entity.getResourceId(),
                entity.getDetails(),
                entity.getOccurredAt()
        );
    }
}
