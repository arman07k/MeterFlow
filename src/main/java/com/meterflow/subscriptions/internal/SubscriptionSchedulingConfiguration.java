package com.meterflow.subscriptions.internal;

import com.meterflow.subscriptions.api.SubscriptionMaintenanceFacade;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers periodic subscription maintenance execution inside the {@code subscriptions} module
 * when {@code meterflow.scheduling.enabled=true} (enabled in {@code local}/{@code supabase},
 * disabled in {@code test}) without introducing any dependency from {@code shared} to {@code subscriptions}.
 */
@Configuration
@ConditionalOnProperty(name = "meterflow.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SubscriptionSchedulingConfiguration implements SchedulingConfigurer {

    private final SubscriptionMaintenanceFacade maintenanceFacade;
    private final Duration maintenanceFixedDelay;

    SubscriptionSchedulingConfiguration(
            SubscriptionMaintenanceFacade maintenanceFacade,
            @Value("${meterflow.subscriptions.maintenance-fixed-delay-ms:60000}") long maintenanceFixedDelayMs
    ) {
        this.maintenanceFacade = maintenanceFacade;
        this.maintenanceFixedDelay = Duration.ofMillis(Math.max(1_000L, maintenanceFixedDelayMs));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(maintenanceFacade::processDueOperations, maintenanceFixedDelay);
    }
}
