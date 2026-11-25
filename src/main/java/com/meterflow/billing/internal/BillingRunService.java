package com.meterflow.billing.internal;

import com.meterflow.billing.api.BillingException;
import com.meterflow.billing.api.BillingResourceNotFoundException;
import com.meterflow.billing.api.BillingRunCommand;
import com.meterflow.billing.api.BillingRunFacade;
import com.meterflow.billing.api.BillingRunStatus;
import com.meterflow.billing.api.BillingRunSummary;
import com.meterflow.billing.api.BillingRunType;
import com.meterflow.billing.api.InvalidBillingRequestException;
import com.meterflow.identity.api.IdentityQueryFacade;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates idempotent, restartable Spring Batch 6 billing runs (FR5, ADR-005, ADR-014).
 *
 * <p>Batch launch and resume methods intentionally run outside an enclosing Spring transaction so
 * Spring Batch's {@code JobRepository} and chunk steps manage their own clean PostgreSQL transactions.
 */
@Service
class BillingRunService implements BillingRunFacade {

    private static final Logger LOGGER = LoggerFactory.getLogger(BillingRunService.class);

    private final BillingRunRepository billingRunRepository;
    private final IdentityQueryFacade identityQueryFacade;
    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Job generateDraftInvoicesJob;
    private final Job finalizeInvoicesJob;
    private final BillingMetrics metrics;

    BillingRunService(
            BillingRunRepository billingRunRepository,
            IdentityQueryFacade identityQueryFacade,
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("generateDraftInvoicesJob") Job generateDraftInvoicesJob,
            @Qualifier("finalizeInvoicesJob") Job finalizeInvoicesJob,
            BillingMetrics metrics
    ) {
        this.billingRunRepository = billingRunRepository;
        this.identityQueryFacade = identityQueryFacade;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.generateDraftInvoicesJob = generateDraftInvoicesJob;
        this.finalizeInvoicesJob = finalizeInvoicesJob;
        this.metrics = metrics;
    }

    @Override
    public BillingRunSummary runDraftGenerationBatch(BillingRunCommand command) {
        return executeBatchRun(BillingRunType.DRAFT_GENERATION, generateDraftInvoicesJob, command);
    }

    @Override
    public BillingRunSummary runFinalizationBatch(BillingRunCommand command) {
        return executeBatchRun(BillingRunType.FINALIZATION, finalizeInvoicesJob, command);
    }

    @Override
    public BillingRunSummary resumeFailedBillingRun(UUID billingRunId) {
        if (billingRunId == null) {
            throw new InvalidBillingRequestException("billingRunId must not be null");
        }
        BillingRunEntity run = billingRunRepository.findById(billingRunId)
                .orElseThrow(() -> new BillingResourceNotFoundException("Billing run not found: " + billingRunId));

        if (run.getStatus() == BillingRunStatus.COMPLETED) {
            return BillingMapper.toBillingRunSummary(run);
        }
        if (run.getStatus() != BillingRunStatus.FAILED) {
            throw new InvalidBillingRequestException(
                    "Only FAILED billing runs can be resumed; current status=" + run.getStatus());
        }
        return resumeRunInternal(run);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BillingRunSummary> findBillingRunById(UUID billingRunId) {
        return billingRunRepository.findById(billingRunId).map(BillingMapper::toBillingRunSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BillingRunSummary> listBillingRuns(Pageable pageable) {
        return billingRunRepository.findAllByOrderByStartedAtDesc(pageable)
                .map(BillingMapper::toBillingRunSummary);
    }

    private BillingRunSummary executeBatchRun(BillingRunType runType, Job job, BillingRunCommand command) {
        UUID customerId = command == null ? null : command.customerId();
        Instant cutoff = command != null && command.cutoffTimestamp() != null
                ? command.cutoffTimestamp().truncatedTo(ChronoUnit.MICROS)
                : nowMicros();
        Integer graceHours = command == null ? null : command.gracePeriodHours();
        String idempotencyKey = command == null ? null : normalizeOptionalKey(command.idempotencyKey());

        if (idempotencyKey != null) {
            Optional<BillingRunEntity> existing = billingRunRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                BillingRunEntity prev = existing.get();
                if (prev.getStatus() == BillingRunStatus.FAILED) {
                    LOGGER.info("Retrying previously FAILED billing run via idempotencyKey: runId={}", prev.getId());
                    return resumeRunInternal(prev);
                }
                return BillingMapper.toBillingRunSummary(prev);
            }
        }

        if (customerId != null) {
            identityQueryFacade.findCustomerById(customerId)
                    .orElseThrow(() -> new BillingResourceNotFoundException("Customer not found: " + customerId));
        }

        BillingRunEntity run = billingRunRepository.saveAndFlush(
                new BillingRunEntity(runType, customerId, cutoff, idempotencyKey, nowMicros()));

        LOGGER.info("Starting billing batch run: runId={} type={} customerScoped={} cutoff={}",
                run.getId(), runType, customerId != null, cutoff);

        JobParameters jobParameters = buildJobParameters(run.getId(), cutoff, customerId, graceHours);

        try {
            JobExecution execution = jobOperator.start(job, jobParameters);
            return recordExecutionOutcome(run, execution);
        } catch (Exception ex) {
            LOGGER.error("Billing batch run failed during launch: runId={} type={} reason={}",
                    run.getId(), runType, ex.getMessage());
            run.fail(ex.getMessage(), 0, 0, 0, 1, nowMicros());
            billingRunRepository.saveAndFlush(run);
            metrics.recordBatchRunFailed();
            throw new BillingException("Billing batch run failed: " + ex.getMessage(), ex);
        }
    }

    private BillingRunSummary resumeRunInternal(BillingRunEntity run) {
        Job targetJob = run.getRunType() == BillingRunType.DRAFT_GENERATION
                ? generateDraftInvoicesJob
                : finalizeInvoicesJob;

        run.markResuming();
        run = billingRunRepository.saveAndFlush(run);

        try {
            JobExecution execution;
            Long prevExecId = run.getBatchJobExecutionId();
            JobExecution previousExecution = prevExecId == null ? null : jobRepository.getJobExecution(prevExecId);

            if (previousExecution != null && previousExecution.getStatus().isUnsuccessful()) {
                LOGGER.info("Restarting failed Spring Batch execution: runId={} previousJobExecutionId={}",
                        run.getId(), prevExecId);
                execution = jobOperator.start(targetJob, previousExecution.getJobParameters());
            } else {
                LOGGER.info("Re-launching billing batch job for failed run: runId={} type={}",
                        run.getId(), run.getRunType());
                JobParameters retryParams = buildJobParameters(
                        run.getId(), run.getCutoffTimestamp(), run.getCustomerId(), null);
                execution = jobOperator.start(targetJob, retryParams);
            }
            return recordExecutionOutcome(run, execution);
        } catch (Exception ex) {
            LOGGER.error("Billing batch run resume failed: runId={} reason={}", run.getId(), ex.getMessage());
            run.fail(ex.getMessage(), run.getProcessedCount(), run.getCreatedCount(),
                    run.getSkippedCount(), Math.max(1, run.getFailedCount()), nowMicros());
            billingRunRepository.saveAndFlush(run);
            metrics.recordBatchRunFailed();
            throw new BillingException("Billing batch run resume failed: " + ex.getMessage(), ex);
        }
    }

    private BillingRunSummary recordExecutionOutcome(BillingRunEntity run, JobExecution execution) {
        run.attachJobExecution(execution.getId());

        int readCount = 0;
        int writeCount = 0;
        int filterCount = 0;
        int skipCount = 0;
        for (StepExecution step : execution.getStepExecutions()) {
            readCount += (int) step.getReadCount();
            writeCount += (int) step.getWriteCount();
            filterCount += (int) step.getFilterCount();
            skipCount += (int) step.getSkipCount();
        }

        if (execution.getStatus() == BatchStatus.COMPLETED) {
            run.complete(readCount, writeCount, filterCount, skipCount, nowMicros());
            metrics.recordBatchRunCompleted();
            LOGGER.info(
                    "Completed billing batch run: runId={} type={} jobExecutionId={} processed={} written={} skipped={} failed={}",
                    run.getId(), run.getRunType(), execution.getId(), readCount, writeCount, filterCount, skipCount);
        } else {
            String failureMsg = execution.getAllFailureExceptions().isEmpty()
                    ? "Batch job finished with status " + execution.getStatus()
                    : execution.getAllFailureExceptions().getFirst().getMessage();
            run.fail(failureMsg, readCount, writeCount, filterCount, Math.max(1, skipCount), nowMicros());
            metrics.recordBatchRunFailed();
            LOGGER.warn(
                    "Billing batch run ended unsuccessfully: runId={} type={} jobExecutionId={} status={} reason={}",
                    run.getId(), run.getRunType(), execution.getId(), execution.getStatus(), failureMsg);
        }
        return BillingMapper.toBillingRunSummary(billingRunRepository.saveAndFlush(run));
    }

    private static JobParameters buildJobParameters(
            UUID billingRunId,
            Instant cutoff,
            UUID customerId,
            Integer graceHours
    ) {
        JobParametersBuilder paramsBuilder = new JobParametersBuilder()
                .addString(BillingBatchJobsConfiguration.PARAM_BILLING_RUN_ID, billingRunId.toString())
                .addString(BillingBatchJobsConfiguration.PARAM_CUTOFF_TIMESTAMP, cutoff.toString());
        if (customerId != null) {
            paramsBuilder.addString(BillingBatchJobsConfiguration.PARAM_CUSTOMER_ID, customerId.toString());
        }
        if (graceHours != null) {
            paramsBuilder.addLong(BillingBatchJobsConfiguration.PARAM_GRACE_HOURS, graceHours.longValue());
        }
        return paramsBuilder.toJobParameters();
    }

    private static String normalizeOptionalKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.trim();
    }

    private static Instant nowMicros() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
