package com.meterflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.meterflow.shared.CacheNames;
import com.meterflow.shared.OutboxRetryScheduler;
import com.meterflow.shared.SchedulingConfiguration;
import io.swagger.v3.oas.models.OpenAPI;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(MeterFlowContextAndFlywayIT.PostgresContainerConfiguration.class)
class MeterFlowContextAndFlywayIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresContainerConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgresContainer() {
            return new PostgreSQLContainer("postgres:17-alpine");
        }
    }

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private JobOperator jobOperator;

    @Autowired
    private OutboxRetryScheduler outboxRetryScheduler;

    @Autowired
    private OpenAPI openApi;

    @Test
    void flywayAppliesV1OutboxAndBatchSchemaAndValidatesCleanly() {
        assertThat(flyway.info().applied())
                .extracting(info -> info.getVersion().getVersion())
                .contains("1");

        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class
        );

        assertThat(tables).contains(
                "event_publication",
                "batch_job_instance",
                "batch_job_execution",
                "batch_job_execution_params",
                "batch_step_execution",
                "batch_step_execution_context",
                "batch_job_execution_context"
        );

        List<String> outboxColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'event_publication'",
                String.class
        );
        assertThat(outboxColumns).contains(
                "id",
                "listener_id",
                "event_type",
                "serialized_event",
                "publication_date",
                "completion_date",
                "status",
                "completion_attempts",
                "last_resubmission_date"
        );
    }

    @Test
    void foundationInfrastructureBeansAreWiredAndSchedulingIsDisabledInTestProfile() {
        assertThat(cacheManager).isInstanceOf(CaffeineCacheManager.class);
        assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder(
                CacheNames.PLAN_VERSIONS,
                CacheNames.METRIC_DEFINITIONS,
                CacheNames.ACTIVE_SUBSCRIPTION_LOOKUP,
                CacheNames.LIVE_ESTIMATES,
                CacheNames.DASHBOARD_SUMMARIES
        );
        assertThat(jobRepository).isNotNull();
        assertThat(jobOperator).isNotNull();
        assertThat(outboxRetryScheduler).isNotNull();
        assertThatCode(outboxRetryScheduler::resubmitIncompleteEvents).doesNotThrowAnyException();
        assertThat(applicationContext.getBeansOfType(SchedulingConfiguration.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ScheduledTaskHolder.class)).isEmpty();
        assertThat(openApi.getInfo().getTitle()).isEqualTo("MeterFlow API");
    }
}
