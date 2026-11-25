package com.meterflow.billing.api;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BillingAdjustmentFacade {

    LateUsageProcessingSummary processPendingLateUsage(UUID customerId);

    BillingAdjustmentRecord createManualAdjustment(CreateManualAdjustmentCommand command);

    Page<BillingAdjustmentRecord> listCustomerAdjustments(UUID customerId, Pageable pageable);
}
