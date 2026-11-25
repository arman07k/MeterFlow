package com.meterflow.payments.internal;

import com.meterflow.payments.api.DunningManagementFacade;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Registers periodic restart-safe dunning retry and suspension processing inside {@code payments}
 * when {@code meterflow.scheduling.enabled=true} (active in {@code local}/{@code supabase},
 * disabled in {@code test} so automated suites drive exact timestamps deterministically).
 */
@Configuration
@ConditionalOnProperty(name = "meterflow.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class PaymentSchedulingConfiguration implements SchedulingConfigurer {

    private final DunningManagementFacade dunningManagementFacade;
    private final Duration workerFixedDelay;

    PaymentSchedulingConfiguration(
            DunningManagementFacade dunningManagementFacade,
            @Value("${meterflow.payments.dunning.worker-fixed-delay-ms:60000}") long workerFixedDelayMs
    ) {
        this.dunningManagementFacade = dunningManagementFacade;
        this.workerFixedDelay = Duration.ofMillis(Math.max(1_000L, workerFixedDelayMs));
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addFixedDelayTask(dunningManagementFacade::processDueDunning, workerFixedDelay);
    }
}
