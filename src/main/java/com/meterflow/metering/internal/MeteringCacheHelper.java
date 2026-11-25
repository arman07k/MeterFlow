package com.meterflow.metering.internal;

import com.meterflow.metering.api.LiveUsageEstimateResult;
import com.meterflow.shared.CacheNames;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit after-commit Caffeine cache coordinator for {@link CacheNames#LIVE_ESTIMATES} (15s TTL, ADR-003, ADR-013).
 *
 * <p>Authoritative billing queries and usage aggregations never read from this cache.
 */
@Component
class MeteringCacheHelper {

    static final long MAX_FRESHNESS_SECONDS = 15L;

    private final CacheManager cacheManager;

    MeteringCacheHelper(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    Optional<LiveUsageEstimateResult> getLiveEstimate(UUID customerId) {
        Cache cache = cacheManager.getCache(CacheNames.LIVE_ESTIMATES);
        if (cache == null || customerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(customerId, LiveUsageEstimateResult.class));
    }

    void putLiveEstimate(UUID customerId, LiveUsageEstimateResult estimate) {
        Cache cache = cacheManager.getCache(CacheNames.LIVE_ESTIMATES);
        if (cache != null && customerId != null && estimate != null) {
            cache.put(customerId, estimate);
        }
    }

    void evictLiveEstimatesAfterCommit(Collection<UUID> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return;
        }
        Set<UUID> snapshot = Set.copyOf(customerIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(snapshot);
                }
            });
        } else {
            evictNow(snapshot);
        }
    }

    private void evictNow(Set<UUID> customerIds) {
        Cache cache = cacheManager.getCache(CacheNames.LIVE_ESTIMATES);
        if (cache == null) {
            return;
        }
        for (UUID customerId : customerIds) {
            if (customerId != null) {
                cache.evict(customerId);
            }
        }
    }
}
