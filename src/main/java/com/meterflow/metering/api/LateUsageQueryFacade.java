package com.meterflow.metering.api;

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Clean downstream read/acknowledge contract for Billing to consume late usage events (FR1, FR6).
 */
public interface LateUsageQueryFacade {

    Page<LateUsageEventRecord> listPendingLateEvents(UUID customerId, Pageable pageable);

    int markLateEventsConsumed(Collection<UUID> lateRecordIds);
}
