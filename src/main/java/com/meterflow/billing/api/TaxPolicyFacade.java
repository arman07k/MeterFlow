package com.meterflow.billing.api;

import java.util.List;
import java.util.UUID;

public interface TaxPolicyFacade {

    TaxPolicySummary configureTaxPolicy(ConfigureTaxPolicyCommand command);

    TaxPolicySummary resolveApplicableTaxPolicy(UUID customerId);

    List<TaxPolicySummary> listTaxPolicies();
}
