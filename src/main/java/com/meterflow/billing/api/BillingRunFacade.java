package com.meterflow.billing.api;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface BillingRunFacade {

    BillingRunSummary runDraftGenerationBatch(BillingRunCommand command);

    BillingRunSummary runFinalizationBatch(BillingRunCommand command);

    BillingRunSummary resumeFailedBillingRun(UUID billingRunId);

    Optional<BillingRunSummary> findBillingRunById(UUID billingRunId);

    Page<BillingRunSummary> listBillingRuns(Pageable pageable);
}
