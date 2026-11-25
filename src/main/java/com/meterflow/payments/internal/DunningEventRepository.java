package com.meterflow.payments.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface DunningEventRepository extends JpaRepository<DunningEventEntity, UUID> {

    List<DunningEventEntity> findByDunningCaseIdOrderByOccurredAtAsc(UUID dunningCaseId);
}
