package com.meterflow.shared;

/**
 * Canonical Caffeine cache region names defined in ADR-003.
 */
public final class CacheNames {

    public static final String PLAN_VERSIONS = "planVersions";
    public static final String METRIC_DEFINITIONS = "metricDefinitions";
    public static final String ACTIVE_SUBSCRIPTION_LOOKUP = "activeSubscriptionLookup";
    public static final String LIVE_ESTIMATES = "liveEstimates";
    public static final String DASHBOARD_SUMMARIES = "dashboardSummaries";

    private CacheNames() {
    }
}
