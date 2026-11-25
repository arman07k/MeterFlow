package com.meterflow.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PlanCatalogFacade {

    PlanVersionDetail createPlanWithDraft(CreatePlanCommand command);

    Page<PricingPlanSummary> listPlans(Pageable pageable);

    Optional<PricingPlanSummary> findPlanById(UUID planId);

    PlanVersionDetail createDraftVersion(UUID planId, Instant effectiveFrom);

    PlanVersionDetail updateDraftVersion(UUID planVersionId, UpdateDraftPlanVersionCommand command);

    PlanVersionDetail publishVersion(UUID planVersionId);

    PlanVersionDetail cloneVersionToDraft(UUID sourcePlanVersionId, Instant effectiveFrom);

    List<PlanVersionDetail> listPlanVersions(UUID planId);

    Optional<PlanVersionDetail> findPlanVersionById(UUID planVersionId);
}
