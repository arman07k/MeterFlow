package com.meterflow.subscriptions.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SubscriptionQueryFacade {

    Optional<SubscriptionSummary> findSubscriptionById(UUID subscriptionId);

    Optional<SubscriptionSummary> findOpenSubscriptionByCustomerId(UUID customerId);

    Map<UUID, SubscriptionSummary> findOpenSubscriptionsByCustomerIds(Collection<UUID> customerIds);

    Page<SubscriptionSummary> listCustomerSubscriptions(UUID customerId, Pageable pageable);

    Page<SubscriptionSummary> listSubscriptions(SubscriptionStatus status, Pageable pageable);

    Map<SubscriptionStatus, Long> countSubscriptionsByStatus();

    Page<SubscriptionSummary> listBillableSubscriptions(Pageable pageable);

    List<SubscriptionSummary> listBillableSubscriptionsAfter(UUID lastSeenId, int limit);

    List<ProrationRecord> listProrationRecords(UUID subscriptionId);

    SubscriptionHistory getSubscriptionHistory(UUID subscriptionId);
}
