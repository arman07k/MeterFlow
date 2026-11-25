package com.meterflow.subscriptions.api;

import java.util.UUID;

public interface SubscriptionManagementFacade {

    SubscriptionSummary createSubscription(CreateSubscriptionCommand command);

    SubscriptionSummary convertTrial(UUID subscriptionId, String idempotencyKey);

    SubscriptionSummary cancelTrial(UUID subscriptionId, String idempotencyKey);

    ImmediatePlanChangeResult changePlanImmediately(UUID subscriptionId, ImmediatePlanChangeCommand command);

    ScheduledPlanChangeRecord schedulePlanChange(UUID subscriptionId, SchedulePlanChangeCommand command);

    ScheduledPlanChangeRecord cancelScheduledPlanChange(UUID subscriptionId, String idempotencyKey);

    SubscriptionSummary pauseSubscription(UUID subscriptionId, String idempotencyKey);

    SubscriptionSummary resumeSubscription(UUID subscriptionId, String idempotencyKey);

    SubscriptionSummary cancelImmediately(UUID subscriptionId, String idempotencyKey);

    SubscriptionSummary cancelAtPeriodEnd(UUID subscriptionId, String idempotencyKey);

    SubscriptionSummary markPastDue(UUID subscriptionId, String reason, String idempotencyKey);

    SubscriptionSummary suspendForNonPayment(UUID subscriptionId, String reason, String idempotencyKey);

    SubscriptionSummary reactivateAfterPayment(UUID subscriptionId, String reason, String idempotencyKey);
}
