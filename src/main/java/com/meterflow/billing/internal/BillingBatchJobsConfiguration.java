package com.meterflow.billing.internal;

import com.meterflow.billing.api.FinalizeInvoiceCommand;
import com.meterflow.billing.api.GenerateDraftInvoiceCommand;
import com.meterflow.billing.api.InvoiceManagementFacade;
import com.meterflow.billing.api.InvoiceStatus;
import com.meterflow.subscriptions.api.SubscriptionQueryFacade;
import com.meterflow.subscriptions.api.SubscriptionStatus;
import com.meterflow.subscriptions.api.SubscriptionSummary;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Restartable, chunk-oriented Spring Batch 6 billing jobs (FR5, ADR-005, ADR-014).
 * Uses bounded paginated database reads (chunk=100) and idempotent invoice operations
 * persisted against Flyway-managed PostgreSQL BATCH_* metadata tables.
 */
@Configuration
class BillingBatchJobsConfiguration {

    static final int CHUNK_SIZE = 100;
    static final String PARAM_BILLING_RUN_ID = "billingRunId";
    static final String PARAM_CUTOFF_TIMESTAMP = "cutoffTimestamp";
    static final String PARAM_CUSTOMER_ID = "customerId";
    static final String PARAM_GRACE_HOURS = "gracePeriodHours";

    @Bean(name = "generateDraftInvoicesJob")
    Job generateDraftInvoicesJob(
            JobRepository jobRepository,
            @Qualifier("generateDraftInvoicesStep") Step generateDraftInvoicesStep
    ) {
        return new JobBuilder("generateDraftInvoicesJob", jobRepository)
                .start(generateDraftInvoicesStep)
                .build();
    }

    @Bean(name = "generateDraftInvoicesStep")
    Step generateDraftInvoicesStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("billableSubscriptionReader") ItemReader<SubscriptionSummary> reader,
            @Qualifier("draftInvoiceProcessor") ItemProcessor<SubscriptionSummary, GenerateDraftInvoiceCommand> processor,
            @Qualifier("draftInvoiceWriter") ItemWriter<GenerateDraftInvoiceCommand> writer
    ) {
        return new StepBuilder("generateDraftInvoicesStep", jobRepository)
                .<SubscriptionSummary, GenerateDraftInvoiceCommand>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .build();
    }

    @Bean(name = "billableSubscriptionReader")
    @StepScope
    ItemReader<SubscriptionSummary> billableSubscriptionReader(
            SubscriptionQueryFacade subscriptionQueryFacade,
            @Value("#{jobParameters['customerId']}") String customerIdParam
    ) {
        UUID filterCustomerId = parseOptionalUuid(customerIdParam);
        return new KeysetBillableSubscriptionReader(subscriptionQueryFacade, filterCustomerId, CHUNK_SIZE);
    }

    @Bean(name = "draftInvoiceProcessor")
    @StepScope
    ItemProcessor<SubscriptionSummary, GenerateDraftInvoiceCommand> draftInvoiceProcessor(
            InvoiceRepository invoiceRepository,
            @Value("#{jobParameters['billingRunId']}") String billingRunIdParam,
            @Value("#{jobParameters['cutoffTimestamp']}") String cutoffParam,
            @Value("#{jobParameters['gracePeriodHours']}") Long graceHoursParam
    ) {
        UUID billingRunId = parseOptionalUuid(billingRunIdParam);
        Instant cutoff = Instant.parse(cutoffParam);
        Integer graceHours = graceHoursParam == null || graceHoursParam < 0 ? null : graceHoursParam.intValue();

        return subscription -> {
            if (subscription.status() != SubscriptionStatus.ACTIVE
                    && subscription.status() != SubscriptionStatus.PAST_DUE) {
                return null;
            }
            if (subscription.currentPeriodStart() == null || subscription.currentPeriodEnd() == null) {
                return null;
            }
            if (subscription.currentPeriodEnd().isAfter(cutoff)) {
                return null;
            }

            Optional<InvoiceEntity> existing = invoiceRepository.findActiveBySubscriptionAndPeriod(
                    subscription.id(),
                    subscription.currentPeriodStart(),
                    subscription.currentPeriodEnd()
            );
            if (existing.isPresent() && existing.get().getStatus() != InvoiceStatus.DRAFT) {
                // Already finalized for this period — filter out cleanly (zero duplicate billing).
                return null;
            }

            return new GenerateDraftInvoiceCommand(
                    subscription.id(),
                    subscription.currentPeriodStart(),
                    subscription.currentPeriodEnd(),
                    graceHours,
                    billingRunId,
                    null
            );
        };
    }

    @Bean(name = "draftInvoiceWriter")
    ItemWriter<GenerateDraftInvoiceCommand> draftInvoiceWriter(InvoiceManagementFacade invoiceManagementFacade) {
        return chunk -> {
            for (GenerateDraftInvoiceCommand command : chunk) {
                invoiceManagementFacade.generateOrRefreshDraft(command);
            }
        };
    }

    @Bean(name = "finalizeInvoicesJob")
    Job finalizeInvoicesJob(
            JobRepository jobRepository,
            @Qualifier("finalizeInvoicesStep") Step finalizeInvoicesStep
    ) {
        return new JobBuilder("finalizeInvoicesJob", jobRepository)
                .start(finalizeInvoicesStep)
                .build();
    }

    @Bean(name = "finalizeInvoicesStep")
    Step finalizeInvoicesStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            @Qualifier("dueDraftInvoiceReader") ItemReader<UUID> reader,
            @Qualifier("finalizeInvoiceWriter") ItemWriter<UUID> writer
    ) {
        return new StepBuilder("finalizeInvoicesStep", jobRepository)
                .<UUID, UUID>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(reader)
                .writer(writer)
                .build();
    }

    @Bean(name = "dueDraftInvoiceReader")
    @StepScope
    ItemReader<UUID> dueDraftInvoiceReader(
            InvoiceRepository invoiceRepository,
            @Value("#{jobParameters['cutoffTimestamp']}") String cutoffParam,
            @Value("#{jobParameters['customerId']}") String customerIdParam
    ) {
        Instant cutoff = Instant.parse(cutoffParam);
        UUID filterCustomerId = parseOptionalUuid(customerIdParam);
        return new KeysetDueDraftInvoiceReader(invoiceRepository, cutoff, filterCustomerId, CHUNK_SIZE);
    }

    @Bean(name = "finalizeInvoiceWriter")
    ItemWriter<UUID> finalizeInvoiceWriter(InvoiceManagementService invoiceManagementService) {
        return chunk -> {
            for (UUID invoiceId : chunk) {
                invoiceManagementService.finalizeInvoiceBatch(invoiceId, new FinalizeInvoiceCommand(true, null, null));
            }
        };
    }

    private static UUID parseOptionalUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return UUID.fromString(value.trim());
    }

    /**
     * Keyset-paginated reader ({@code id > lastSeenId}) over billable subscriptions for O(N) scanning
     * without quadratic OFFSET queries or repeated COUNT(*) queries across large customer batches.
     */
    private static final class KeysetBillableSubscriptionReader implements ItemReader<SubscriptionSummary> {

        private final SubscriptionQueryFacade subscriptionQueryFacade;
        private final UUID filterCustomerId;
        private final int pageSize;
        private int customerPage = 0;
        private UUID lastSeenId = null;
        private boolean exhausted = false;
        private Iterator<SubscriptionSummary> currentIterator = List.<SubscriptionSummary>of().iterator();

        private KeysetBillableSubscriptionReader(
                SubscriptionQueryFacade subscriptionQueryFacade,
                UUID filterCustomerId,
                int pageSize
        ) {
            this.subscriptionQueryFacade = subscriptionQueryFacade;
            this.filterCustomerId = filterCustomerId;
            this.pageSize = pageSize;
        }

        @Override
        public SubscriptionSummary read() {
            while (!currentIterator.hasNext()) {
                if (exhausted) {
                    return null;
                }
                List<SubscriptionSummary> batch;
                if (filterCustomerId == null) {
                    batch = subscriptionQueryFacade.listBillableSubscriptionsAfter(lastSeenId, pageSize);
                    if (batch.isEmpty()) {
                        exhausted = true;
                        return null;
                    }
                    lastSeenId = batch.getLast().id();
                    if (batch.size() < pageSize) {
                        exhausted = true;
                    }
                } else {
                    Page<SubscriptionSummary> page = subscriptionQueryFacade.listCustomerSubscriptions(
                            filterCustomerId,
                            PageRequest.of(customerPage++, pageSize)
                    );
                    if (!page.hasContent()) {
                        exhausted = true;
                        return null;
                    }
                    if (!page.hasNext()) {
                        exhausted = true;
                    }
                    batch = page.getContent();
                }
                currentIterator = batch.iterator();
            }
            return currentIterator.next();
        }
    }

    /**
     * Keyset-paginated reader ({@code id > lastSeenId}) over due DRAFT invoices so transitioning
     * rows out of DRAFT status during chunk writes never causes page-offset skipping.
     */
    private static final class KeysetDueDraftInvoiceReader implements ItemReader<UUID> {

        private final InvoiceRepository invoiceRepository;
        private final Instant cutoff;
        private final UUID filterCustomerId;
        private final int pageSize;
        private UUID lastSeenId = null;
        private boolean exhausted = false;
        private Iterator<UUID> currentIterator = List.<UUID>of().iterator();

        private KeysetDueDraftInvoiceReader(
                InvoiceRepository invoiceRepository,
                Instant cutoff,
                UUID filterCustomerId,
                int pageSize
        ) {
            this.invoiceRepository = invoiceRepository;
            this.cutoff = cutoff;
            this.filterCustomerId = filterCustomerId;
            this.pageSize = pageSize;
        }

        @Override
        public UUID read() {
            while (!currentIterator.hasNext()) {
                if (exhausted) {
                    return null;
                }
                Page<InvoiceEntity> page = invoiceRepository.findDraftsDueForFinalizationAfter(
                        cutoff,
                        filterCustomerId,
                        lastSeenId,
                        PageRequest.of(0, pageSize)
                );
                if (!page.hasContent()) {
                    exhausted = true;
                    return null;
                }
                List<UUID> ids = page.getContent().stream().map(InvoiceEntity::getId).toList();
                lastSeenId = ids.getLast();
                if (ids.size() < pageSize) {
                    exhausted = true;
                }
                currentIterator = ids.iterator();
            }
            return currentIterator.next();
        }
    }
}
