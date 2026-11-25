package com.meterflow.payments.api;

import java.time.Instant;

public interface DunningManagementFacade {

    DunningExecutionSummary processDueDunning();

    DunningExecutionSummary processDueDunning(Instant referenceTime, MockPaymentOutcome retryOutcomeOverride);
}
