package com.meterflow.shared;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Enables Spring scheduling and registers periodic foundation tasks when
 * {@code meterflow.scheduling.enabled=true} (enabled in {@code local}/{@code supabase},
 * disabled in {@code test}).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "meterflow.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration implements SchedulingConfigurer {

    private final OutboxRetryScheduler outboxRetryScheduler;
    private final Duration retryFixedDelay;

    public SchedulingConfiguration(
            OutboxRetryScheduler outboxRetryScheduler,
            @Value("${meterflow.outbox.retry-fixed-delay-ms:15000}") long retryFixedDelayMs
    ) {
        this.outboxRetryScheduler = outboxRetryScheduler;
        this.retryFixedDelay = Duration.ofMillis(Math.max(1_000L, retryFixedDelayMs));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(outboxRetryScheduler::resubmitIncompleteEvents, retryFixedDelay);
    }
}
