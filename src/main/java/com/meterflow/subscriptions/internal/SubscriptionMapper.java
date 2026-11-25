package com.meterflow.subscriptions.internal;

import com.meterflow.shared.Money;
import com.meterflow.subscriptions.api.ProrationRecord;
import com.meterflow.subscriptions.api.ScheduledPlanChangeRecord;
import com.meterflow.subscriptions.api.SubscriptionPlanAssignmentRecord;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import com.meterflow.subscriptions.api.SubscriptionTransitionRecord;

final class SubscriptionMapper {

    private SubscriptionMapper() {
    }

    static SubscriptionSummary toSummary(SubscriptionEntity entity) {
        return new SubscriptionSummary(
                entity.getId(),
                entity.getCustomerId(),
                entity.getCurrentPlanVersionId(),
                entity.getCurrency(),
                entity.getBillingInterval(),
                entity.getBillingTimezone(),
                entity.getSeatQuantity(),
                entity.getStatus(),
                entity.getTrialStartAt(),
                entity.getTrialEndAt(),
                entity.getTrialConversionPolicy(),
                entity.getCurrentPeriodStart(),
                entity.getCurrentPeriodEnd(),
                entity.getPausedAt(),
                entity.getPausedRemainingSeconds(),
                entity.isCancelAtPeriodEnd(),
                entity.getCancelledAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    static SubscriptionPlanAssignmentRecord toAssignmentRecord(SubscriptionPlanAssignmentEntity entity) {
        return new SubscriptionPlanAssignmentRecord(
                entity.getId(),
                entity.getSubscriptionId(),
                entity.getPlanVersionId(),
                entity.getSeatQuantity(),
                entity.getEffectiveFrom(),
                entity.getEffectiveTo(),
                entity.getAssignmentReason(),
                entity.getCreatedAt()
        );
    }

    static ScheduledPlanChangeRecord toScheduledChangeRecord(SubscriptionScheduledChangeEntity entity) {
        return new ScheduledPlanChangeRecord(
                entity.getId(),
                entity.getSubscriptionId(),
                entity.getCurrentPlanVersionId(),
                entity.getTargetPlanVersionId(),
                entity.getTargetSeatQuantity(),
                entity.getChangeDirection(),
                entity.getEffectiveAt(),
                entity.getStatus(),
                entity.getIdempotencyKey(),
                entity.getAppliedAt(),
                entity.getCancelledAt(),
                entity.getCreatedAt()
        );
    }

    static ProrationRecord toProrationRecord(SubscriptionProrationRecordEntity entity) {
        return new ProrationRecord(
                entity.getId(),
                entity.getSubscriptionId(),
                entity.getPreviousPlanVersionId(),
                entity.getNewPlanVersionId(),
                entity.getPreviousSeatQuantity(),
                entity.getNewSeatQuantity(),
                entity.getPeriodStart(),
                entity.getPeriodEnd(),
                entity.getEffectiveAt(),
                entity.getTotalPeriodSeconds(),
                entity.getRemainingSeconds(),
                entity.getProrationFactor(),
                Money.of(entity.getPreviousPlanRecurringAmount(), entity.getCurrency()),
                Money.of(entity.getNewPlanRecurringAmount(), entity.getCurrency()),
                Money.of(entity.getCreditAmount(), entity.getCurrency()),
                Money.of(entity.getDebitAmount(), entity.getCurrency()),
                Money.of(entity.getNetProrationAmount(), entity.getCurrency()),
                entity.getCurrency(),
                entity.getNonProratableUsageCharges(),
                entity.getCalculationPolicy(),
                entity.getIdempotencyKey(),
                entity.getCreatedAt()
        );
    }

    static SubscriptionTransitionRecord toTransitionRecord(SubscriptionStateTransitionEntity entity) {
        return new SubscriptionTransitionRecord(
                entity.getId(),
                entity.getSubscriptionId(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getReason(),
                entity.getEffectiveAt(),
                entity.getIdempotencyKey(),
                entity.getMetadata(),
                entity.getCreatedAt()
        );
    }
}
