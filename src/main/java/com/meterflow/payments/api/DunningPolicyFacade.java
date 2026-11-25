package com.meterflow.payments.api;

import java.util.List;

public interface DunningPolicyFacade {

    DunningPolicySummary configurePolicy(ConfigureDunningPolicyCommand command);

    DunningPolicySummary getActivePolicy();

    List<DunningPolicySummary> listPolicies();
}
