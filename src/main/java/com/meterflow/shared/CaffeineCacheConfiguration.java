package com.meterflow.shared;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures bounded Caffeine in-memory cache regions per ADR-003.
 * Authoritative billing never reads cached live estimates.
 */
@Configuration
@EnableCaching
public class CaffeineCacheConfiguration {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);

        manager.registerCustomCache(
                CacheNames.PLAN_VERSIONS,
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfterWrite(Duration.ofMinutes(60))
                        .recordStats()
                        .build()
        );
        manager.registerCustomCache(
                CacheNames.METRIC_DEFINITIONS,
                Caffeine.newBuilder()
                        .maximumSize(1_000)
                        .expireAfterWrite(Duration.ofMinutes(15))
                        .recordStats()
                        .build()
        );
        manager.registerCustomCache(
                CacheNames.ACTIVE_SUBSCRIPTION_LOOKUP,
                Caffeine.newBuilder()
                        .maximumSize(5_000)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .recordStats()
                        .build()
        );
        manager.registerCustomCache(
                CacheNames.LIVE_ESTIMATES,
                Caffeine.newBuilder()
                        .maximumSize(5_000)
                        .expireAfterWrite(Duration.ofSeconds(15))
                        .recordStats()
                        .build()
        );
        manager.registerCustomCache(
                CacheNames.DASHBOARD_SUMMARIES,
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(Duration.ofSeconds(30))
                        .recordStats()
                        .build()
        );

        return manager;
    }
}
