package com.meterflow.subscriptions.api;

import java.util.List;

public record SubscriptionHistory(
        SubscriptionSummary subscription,
        List<SubscriptionPlanAssignmentRecord> planAssignments,
        List<ScheduledPlanChangeRecord> scheduledChanges,
        List<ProrationRecord> prorationRecords,
        List<SubscriptionTransitionRecord> stateTransitions
) {
    public SubscriptionHistory {
        planAssignments = planAssignments == null ? List.of() : List.copyOf(planAssignments);
        scheduledChanges = scheduledChanges == null ? List.of() : List.copyOf(scheduledChanges);
        prorationRecords = prorationRecords == null ? List.of() : List.copyOf(prorationRecords);
        stateTransitions = stateTransitions == null ? List.of() : List.copyOf(stateTransitions);
    }
}
