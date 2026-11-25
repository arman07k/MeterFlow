package com.meterflow.notifications.internal;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Page<AuditLogEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditLogEntity> findByCustomerIdOrderByOccurredAtDesc(UUID customerId, Pageable pageable);
}
