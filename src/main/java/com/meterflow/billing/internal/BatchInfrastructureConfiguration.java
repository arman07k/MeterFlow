package com.meterflow.billing.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

/**
 * Spring Batch 6 foundation task executor configuration (ADR-005).
 * Spring Boot 4's spring-boot-starter-batch-jdbc auto-configures the PostgreSQL
 * JobRepository and JobOperator against Flyway-managed BATCH_* tables.
 * Domain billing jobs are implemented in subsequent dedicated tasks.
 */
@Configuration
public class BatchInfrastructureConfiguration {

    @Bean(name = "meterflowBatchTaskExecutor")
    public TaskExecutor meterflowBatchTaskExecutor(
            @Value("${meterflow.batch.concurrency-limit:2}") int concurrencyLimit
    ) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("meterflow-batch-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(Math.max(1, concurrencyLimit));
        return executor;
    }
}
