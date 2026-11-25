package com.meterflow.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.meterflow.billing.internal.BatchInfrastructureConfiguration;
import com.meterflow.portal.internal.OpenApiConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.config.ScheduledTaskHolder;

class SharedFoundationUnitTests {

    @Test
    void moneyEnforcesExplicitPersistedRoundingAndSupportedCurrencyMatching() {
        Money roundedUp = Money.of(new BigDecimal("10.12345"), "usd").roundToPersisted();
        Money roundedDown = Money.of(new BigDecimal("10.12344"), "USD").roundToPersisted();

        assertThat(roundedUp.currencyCode()).isEqualTo("USD");
        assertThat(roundedUp.amount()).isEqualByComparingTo("10.1235");
        assertThat(roundedDown.amount()).isEqualByComparingTo("10.1234");
        assertThat(roundedUp.add(roundedDown).amount()).isEqualByComparingTo("20.2469");

        Money bdt = Money.of(new BigDecimal("5.00"), "BDT");
        assertThatThrownBy(() -> roundedUp.add(bdt))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void clockConfigurationProvidesUtcClock() {
        ClockConfiguration config = new ClockConfiguration();
        assertThat(config.systemUtcClock().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void caffeineCacheManagerRegistersAllFiveRequiredCaches() {
        CaffeineCacheConfiguration config = new CaffeineCacheConfiguration();
        CacheManager cacheManager = config.cacheManager();

        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheNames.PLAN_VERSIONS,
                CacheNames.METRIC_DEFINITIONS,
                CacheNames.ACTIVE_SUBSCRIPTION_LOOKUP,
                CacheNames.LIVE_ESTIMATES,
                CacheNames.DASHBOARD_SUMMARIES
        );
    }

    @Test
    void outboxRetrySchedulerResubmitsIncompletePublicationsOlderThanConfiguredAge() {
        IncompleteEventPublications incomplete = mock(IncompleteEventPublications.class);
        OutboxRetryScheduler scheduler = new OutboxRetryScheduler(incomplete, 10);

        scheduler.resubmitIncompleteEvents();

        verify(incomplete).resubmitIncompletePublicationsOlderThan(Duration.ofSeconds(10));
    }

    @Test
    void batchTaskExecutorEnforcesPositiveConcurrencyLimit() {
        BatchInfrastructureConfiguration config = new BatchInfrastructureConfiguration();
        SimpleAsyncTaskExecutor executor = (SimpleAsyncTaskExecutor) config.meterflowBatchTaskExecutor(1);

        assertThat(executor.getConcurrencyLimit()).isEqualTo(1);
    }

    @Test
    void openApiConfigurationDefinesMeterFlowMetadataAndBasicSecurityScheme() {
        OpenApiConfiguration config = new OpenApiConfiguration();
        OpenAPI openApi = config.meterFlowOpenApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("MeterFlow API");
        assertThat(openApi.getComponents().getSecuritySchemes())
                .containsKey(OpenApiConfiguration.BASIC_AUTH_SCHEME);
    }

    @Test
    void schedulingRegistersOutboxTaskWhenEnabledAndSkipsItWhenDisabled() {
        IncompleteEventPublications incomplete = mock(IncompleteEventPublications.class);
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(IncompleteEventPublications.class, () -> incomplete)
                .withUserConfiguration(OutboxRetryScheduler.class, SchedulingConfiguration.class);

        runner.withPropertyValues("meterflow.scheduling.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxRetryScheduler.class);
                    assertThat(context).hasSingleBean(SchedulingConfiguration.class);
                    assertThat(context).hasSingleBean(ScheduledTaskHolder.class);
                    assertThat(context.getBean(ScheduledTaskHolder.class).getScheduledTasks()).hasSize(1);
                });

        runner.withPropertyValues("meterflow.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxRetryScheduler.class);
                    assertThat(context).doesNotHaveBean(SchedulingConfiguration.class);
                    assertThat(context).doesNotHaveBean(ScheduledTaskHolder.class);
                });
    }
}
