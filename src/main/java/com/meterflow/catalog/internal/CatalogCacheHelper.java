package com.meterflow.catalog.internal;

import com.meterflow.catalog.api.BillableMetricDefinition;
import com.meterflow.catalog.api.PlanVersionDetail;
import com.meterflow.catalog.api.PlanVersionStatus;
import com.meterflow.shared.CacheNames;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Bounded Caffeine cache helper for immutable/read-heavy catalog projections (ADR-003).
 *
 * <p>Only immutable PUBLISHED plan versions are cached in {@code planVersions}. Mutable metric
 * definitions are evicted strictly after transaction commit. Authoritative rating never trusts
 * cached data as financial authority.
 */
@Component
class CatalogCacheHelper {

    private final CacheManager cacheManager;

    CatalogCacheHelper(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    Optional<BillableMetricDefinition> getMetric(UUID metricId) {
        Cache cache = cacheManager.getCache(CacheNames.METRIC_DEFINITIONS);
        if (cache == null || metricId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(metricId, BillableMetricDefinition.class));
    }

    void putMetric(BillableMetricDefinition metric) {
        Cache cache = cacheManager.getCache(CacheNames.METRIC_DEFINITIONS);
        if (cache != null && metric != null && metric.id() != null) {
            cache.put(metric.id(), metric);
        }
    }

    void evictMetricAfterCommit(UUID metricId) {
        if (metricId == null) {
            return;
        }
        runAfterCommit(() -> {
            Cache cache = cacheManager.getCache(CacheNames.METRIC_DEFINITIONS);
            if (cache != null) {
                cache.evict(metricId);
            }
        });
    }

    Optional<PlanVersionDetail> getPublishedPlanVersion(UUID planVersionId) {
        Cache cache = cacheManager.getCache(CacheNames.PLAN_VERSIONS);
        if (cache == null || planVersionId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(planVersionId, PlanVersionDetail.class));
    }

    void putIfPublished(PlanVersionDetail detail) {
        if (detail == null || detail.status() != PlanVersionStatus.PUBLISHED || detail.id() == null) {
            return;
        }
        Cache cache = cacheManager.getCache(CacheNames.PLAN_VERSIONS);
        if (cache != null) {
            cache.put(detail.id(), detail);
        }
    }

    void evictPlanVersionAfterCommit(UUID planVersionId) {
        if (planVersionId == null) {
            return;
        }
        runAfterCommit(() -> {
            Cache cache = cacheManager.getCache(CacheNames.PLAN_VERSIONS);
            if (cache != null) {
                cache.evict(planVersionId);
            }
        });
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
