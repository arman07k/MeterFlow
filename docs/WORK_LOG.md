# MeterFlow Engineering Work Log (`docs/WORK_LOG.md`)

## 1. Purpose & Governance

This log records chronological, auditable engineering and architecture activities across **MeterFlow** (`C12`). Every session must append a factual entry before concluding, per [`AGENT.md`](../AGENT.md).

### Entry Rules

- Record only **actual inspections, edits, and commands executed**.
- Never fabricate test passes, coverage figures, or completed code.
- Use **portable repository-relative Markdown links** (`./...`, `../...`); never record secrets, PATs, or workstation-specific absolute paths.
- Organize entries by date, deliverable/feature scope, and requirement IDs (`FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`) without sequential development-tracking numbers or release-gate labels.

---

## 2. Reusable Session Entry Template

```markdown
### YYYY-MM-DD — <Feature / Deliverable Summary>

- **Contributor(s)**: <Human / AI Agent Identifier>
- **Deliverable Target**: <Exact Deliverable Name from PLAN.md>
- **Requirement IDs**: <e.g., FR1, FR2, QA1, QA2, C1, C5>
- **Summary of Changes**:
  - <Concise factual summary of created/updated relative paths>
- **Verification Executed**:
  - <Exact commands or audits executed and factual outcome>
- **Risks / Open Items**:
  - <Remaining risks, assumptions, or next steps>
```

---

## 3. Chronological Log Entries

### 2026-11-25 — Final Pre-Submission Engineering Audit, Security/UI Hardening, Billing/Metering Optimization & Full-Scale QA1/QA2/QA3 Verification

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: Full-Repository Pre-Submission Engineering Audit & Scale Benchmark Verification ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`
- **Summary of Changes**:
  - **API Pagination & Security Fixes**: Replaced raw unbounded `Pageable` / `PageImpl` serialization in `WebhookController` and `NotificationController` with clamped `size` (`1..100`) and stable `PageResponse.from(...)` (eliminating unbounded-page DoS risk and Spring Data Jackson 3 `PageImpl` warnings); added pre-mutation customer ownership verification (`markAsReadForCustomer`) and O(1)-memory bulk JPQL update (`markAllUnreadAsRead`) in `NotificationService`/`NotificationRepository`.
  - **Deployment & UI/Accessibility Fixes**: Forwarded `WEBHOOK_SECRET_ENCRYPTION_KEY` and `METERFLOW_BOOTSTRAP_ADMIN_*` environment variables in `compose.yaml`; added `:focus-visible` keyboard accessibility outlines to `meterflow.css`; added `flashMessages` to `index.html` for visible `403`/`404` portal error feedback; wired Previous/Next pagination controls across all remaining paginated Customer and Operator portal tables.
  - **Justified Performance Optimizations**: Replaced `O(N^2)` OFFSET `PaginatedBillableSubscriptionReader` with `O(N)` keyset `KeysetBillableSubscriptionReader` (`listBillableSubscriptionsAfter`); replaced 5-query `getSubscriptionHistory` call inside `InvoiceManagementService.generateOrRefreshDraft` with single-query `listProrationRecords` (saving 40,000 SQL queries at 10,000 customers); added `finalizeInvoiceBatch` avoiding 20,000 unused `loadInvoiceDetail` reloads during batch finalization; moved routine per-invoice logs to `DEBUG`; avoided redundant `LinkedHashMap` allocations in `UsageIngestionService.buildEffectiveProperties`.
  - **Full-Scale Reproducible Benchmark**: Added opt-in `QaScaleBenchmarkIT` (`-Dmeterflow.scale.benchmark=true`) and synchronized honest traceability statuses and measured full-scale numbers across `README.md`, `docs/DEFENCE_GUIDE.md`, `docs/REQUIREMENTS_TRACEABILITY.md`, `MEMORY.md`, `PLAN.md`, and `CHANGELOG.md`.
- **Verification Executed**:
  - `.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify -Dit.test=QaScaleBenchmarkIT -Dmeterflow.scale.benchmark=true` — **`BUILD SUCCESS`** (`07:56 min`, `2/2` PASS on PostgreSQL `17.12` Testcontainers):
    - **QA1 + QA2**: `1,000,000` unique events + `30,000` duplicates (3%) + `20,000` late events (2%) + `10,000` batch-250 events (`0` errors, `143 MiB` heap used), **`16,586.5 events/sec`** (`60,290 ms`), batch-250 **`p50=35 ms`, `p95=46 ms`, `p99=49 ms`**, batch-1,000 **`p50=204 ms`, `p95=295 ms`, `p99=371 ms`**, **0.00 paisa** financial reconciliation across all 10 customers (`6,050.00 BDT` exact per customer).
    - **QA3**: **`10,000` customers/subscriptions** via Spring Batch 6 (`chunk=100`, keyset pagination): draft **`181,649 ms` (`3m 01.6s`)** + finalization **`160,564 ms` (`2m 40.6s`)** = **`342,213 ms` (`5m 42.2s` < 10-minute SLA)**, `163 MiB` heap used, idempotent re-run **`197 ms`** (`0` duplicate invoices, `finalized_invoices=10000`).
  - Rebuilt Docker Compose smoke test (`docker compose config -q`, `docker compose --profile full up --build -d`): `meterflow-app` healthy (`387.8 MiB / 512 MiB`), `meterflow-postgres` healthy (`51.71 MiB / 512 MiB`), `/actuator/health/readiness` `HTTP 200` (`{"status":"UP"}`), `/` `HTTP 200` (`CSP: default-src 'self'; frame-ancestors 'none'`), `/login` `HTTP 200`, `/css/meterflow.css` `HTTP 200`.
- **Risks / Open Items**:
  - Host `k6` CLI not installed; live `supabase` profile not connected; no Git commit or push performed.

---

### 2026-11-25 — Complete Remaining Product Scope: FR9 Webhooks & Notifications, FR10 Historical Simulation, Customer & Operator Thymeleaf Portals, QA1–QA5 Validation & Defence Documentation (`V8`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Notifications & Outbound HMAC Webhooks Module`, `FR10 Historical Invoice Simulation`, `Portal Module: Spring MVC + Thymeleaf UI`, and `End-to-End Integration, Security & QA Engineering Verification Suite` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR7`, `FR9`, `FR10`, `QA1`–`QA5`, `C1`–`C12`
- **Summary of Changes**:
  - Created Flyway [`V8__webhooks_and_notifications_schema.sql`](../src/main/resources/db/migration/V8__webhooks_and_notifications_schema.sql) (`webhook_endpoints`, `webhook_deliveries`, immutable `webhook_delivery_attempts`, `notifications`, and immutable `audit_logs` with PostgreSQL `BEFORE UPDATE OR DELETE` immutability triggers), preserving `V1`–`V7` untouched.
  - Implemented encapsulated `com.meterflow.webhooks` (`webhooks.api` + `webhooks.internal`: AES-256-GCM `WebhookSecretCipher`, one-time secret disclosure, 24h dual-key `HMAC-SHA256` rotation & constant-time `WebhookSignatureVerifier`, `SsrfUrlValidator`, `WebhookBackoffPolicy` `+30s/+2m/+10m/+1h/+6h`, `FOR UPDATE SKIP LOCKED` `WebhookDeliveryExecutor`/`WebhookDeliveryService`, `@ApplicationModuleListener` `WebhookDomainEventListener`, auditable manual replay emitting `WebhookReplayAuditEvent`, `WebhookMetrics`) and `com.meterflow.notifications` (idempotent customer notifications + immutable audit logs), plus thin REST adapters `WebhookController` & `NotificationController`.
  - Implemented `FR10` strictly read-only (`@Transactional(readOnly = true)`) `HistoricalInvoiceSimulationFacade` / `HistoricalInvoiceSimulationService` in `com.meterflow.billing`, exposed via `POST /api/v1/billing/simulations/historical` and `POST /api/v1/customer/{customerId}/billing/simulations/historical`.
  - Built full server-rendered Thymeleaf **Customer Portal** (`/customer/**`, 11 views, read-only payments, one-time webhook secret banner, historical simulator) and **Admin/Operator Console** (`/operator/**`, `/admin/**`, 9 views) with external `static/css/meterflow.css` (zero inline styles/scripts under strict `CSP: default-src 'self'; frame-ancestors 'none'`), CSRF protection on all POST forms, and `PortalExceptionHandler` (`403`/`404`).
  - Fixed PostgreSQL untyped null-parameter query handling in `UsageEventRepository` / `UsageQueryService`, added non-breaking portal query helpers on `IdentityQueryFacade` and `SubscriptionQueryFacade`, and added `Money.formatted()` (`@JsonIgnore`).
  - Added `WebhookSecurityAndBackoffUnitTests` (`4/4`), `WebhookNotificationAndSimulationIT` (`1/1` with live local JDK `HttpServer`), `PortalUiIT` (`1/1`), `QaEngineeringValidationIT` (`1/1`), [`README.md`](../README.md), and [`docs/DEFENCE_GUIDE.md`](./DEFENCE_GUIDE.md), and updated `ModularityTests` (`10` modules) and `MeterFlowContextAndFlywayIT` (`V1`–`V8`).
- **Verification Executed**:
  - `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`02:51 min`, `432` main + `22` test Java sources, `0` compiler warnings):
    - Surefire (`63/63`, `0` failures/errors/skipped): all `12` unit/security/Modulith test suites including `ModularityTests` (`1/1` across `10` modules) and `WebhookSecurityAndBackoffUnitTests` (`4/4`).
    - Failsafe (`37/37`, `0` failures/errors/skipped): all `10` PostgreSQL 17 Testcontainers IT suites including `MeterFlowContextAndFlywayIT` (`V1`–`V8`), `WebhookNotificationAndSimulationIT`, `PortalUiIT`, and `QaEngineeringValidationIT`.
  - `.\mvnw.cmd -B -Pjava21 clean test-compile` — **`BUILD SUCCESS`** (`11.699 s`, all `432` main + `22` test sources compiled cleanly with `--release 21`).
  - Measured `QaEngineeringValidationIT` metrics on real PostgreSQL 17 Testcontainers: `qa1_reconciled=true` (`5,000` unique + `150` dup + `100` late, `0.00` paisa drift), `qa2_events_per_sec=7587.3` (`659 ms`, batch-1000 `p50=127 ms`, `p95=149 ms`, `p99=149 ms`), `qa3_customers=50` (`draft=2373 ms`, `finalize=1673 ms`, `0` duplicate invoices on re-run), `qa5_estimate_deviation_pct=0.0000`.
  - Docker Compose runtime smoke test (`docker compose config -q`, `docker compose --profile full up --build -d`): `meterflow-postgres` (`56.16 MiB / 512 MiB`, healthy) + `meterflow-app` (`358 MiB / 512 MiB`, healthy, `Started MeterFlowApplication in 19.821 seconds`), `/actuator/health/readiness` `HTTP 200` (`UP`), `/` `HTTP 200` (`Content-Security-Policy: default-src 'self'; frame-ancestors 'none'`), `/css/meterflow.css` `HTTP 200`.
- **Risks / Open Items**:
  - Host `k6` CLI is not installed locally (`perf/k6/*.js` authored and ready for a `k6`-equipped host); Supabase profile not connected; no Git commit or push performed per repository governance.

---

### 2026-11-25 — Payments & Dunning Engine: Mock Payment Processing, Day 1/3/7 Retries, Separate Suspension Timer & Recovery (`V7`, `ADR-015`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Payments Module: Mock Collection, Day 1/3/7 Retries & Dunning Workflow` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR8`, `QA2`–`QA5`, `C1`, `C5`–`C9`
- **Summary of Changes**:
  - Authored [`ADR-015`](./architecture/ADRs/ADR-015-payments-mock-processor-dunning-retries-and-suspension.md) and indexed it in [`ADRs/README.md`](./architecture/ADRs/README.md).
  - Created Flyway [`V7__payments_and_dunning_schema.sql`](../src/main/resources/db/migration/V7__payments_and_dunning_schema.sql) (forward extension of `subscriptions` constraints for `SUSPENDED`, `payment_reference_seq`, `dunning_policies` seeded with `DEFAULT_DAY_1_3_7`, `dunning_cases`, `payment_transactions` with `uq_captured_payment_per_invoice`, append-only `payment_attempts` & `dunning_events`, `FOR UPDATE SKIP LOCKED` indexes, and `3` PostgreSQL immutability triggers), preserving `V1`–`V6` untouched.
  - Updated `payments/package-info.java` (`allowedDependencies = { "shared", "identity :: api", "subscriptions :: api", "billing :: api" }`) and added non-breaking settlement/dunning contract extensions to `InvoiceManagementFacade` (`recordPaymentSettlement`, `markInvoicePastDue`) and `SubscriptionManagementFacade` (`SUSPENDED`, `markPastDue`, `suspendForNonPayment`, `reactivateAfterPayment`).
  - Implemented `com.meterflow.payments.api` (`29` contracts: enums, exceptions, DTOs/commands, `5` outbox events `PaymentSucceededEvent`/`PaymentFailedEvent`/`PaymentRetryScheduledEvent`/`SubscriptionSuspendedForNonPaymentEvent`/`SubscriptionReactivatedAfterPaymentEvent`, and `4` facades) and `com.meterflow.payments.internal` (`5` entities, `5` repositories, `PaymentMapper`, `MockPaymentGateway`, `DunningScheduleCalculator`, `PaymentMetrics`, `DunningPolicyService`, `PaymentProcessingService`, `DunningWorkerService`, `PaymentQueryService`, `PaymentSchedulingConfiguration`, `PaymentAuditEventListener`).
  - Added thin customer-isolated `PaymentController` (`/api/v1/payments/**`, `/api/v1/dunning/**`, `/api/v1/customer/{customerId}/payments/**`, `/api/v1/customer/{customerId}/dunning/**` with zero simulation controls exposed to customers), RFC 9457 payment error mappings in `GlobalApiExceptionHandler`, `PaymentAndDunningUnitTests` (`6/6`), and PostgreSQL 17 Testcontainers `PaymentModuleIT` (`6/6`), and expanded `ModularityTests` (`1/1`) and `MeterFlowContextAndFlywayIT` (`2/2`).
- **Verification Executed**:
  - `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`02:24 min`, `374` main + `18` test Java sources, `0` compiler warnings):
    - Surefire (`59/59`, `0` failures/errors/skipped): `BillingUnitTests` (`8/8`), `MetricDefinitionUnitTests` (`4/4`), `PricingEngineUnitTests` (`6/6`), `IdentityUnitTests` (`3/3`), `SecurityFoundationTests` (`8/8`), `MeteringUnitTests` (`5/5`), `ModularityTests` (`1/1`), `PaymentAndDunningUnitTests` (`6/6`), `MoneyUnitTests` (`6/6`), `SharedFoundationUnitTests` (`7/7`), `BillingPeriodAndProrationUnitTests` (`5/5`).
    - Failsafe (`34/34`, `0` failures/errors/skipped): `BillingModuleIT` (`8/8`, `32.84 s`), `CatalogModuleIT` (`3/3`, `10.36 s`), `IdentityAndSecurityIT` (`4/4`, `10.71 s`), `MeterFlowContextAndFlywayIT` (`2/2`, `7.467 s`), `MeteringModuleIT` (`6/6`, `18.10 s`), `PaymentModuleIT` (`6/6`, `11.63 s`), `SubscriptionModuleIT` (`5/5`, `9.842 s`) on Testcontainers PostgreSQL `17.12` validating `V1`–`V7` and Hibernate `7.2.7.Final` `ddl-auto=validate`.
  - `.\mvnw.cmd -B -Pjava21 clean test-compile` — **`BUILD SUCCESS`** (`19.072 s`, all `374` main + `18` test files compiled cleanly with `--release 21`).
- **Risks / Open Items**:
  - Outbound `HMAC-SHA256` webhook delivery (`FR9`, `V8`), notification delivery, full historical invoice comparison (`FR10`), and Thymeleaf portals remain scoped to upcoming deliverables; no Git commit/push or Supabase connection performed.

---

### 2026-11-25 — Invoicing & Billing Engine: Spring Batch Draft/Final Invoices, Tax, Prepaid Credits, Credit Notes & Late Adjustments (`V6`, `ADR-014`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Billing Module: Spring Batch Draft/Final Invoices, Tax, Credits & Adjustments` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR5`, `FR6`, `QA1`–`QA5`, `C1`, `C5`, `C8`, `C9`
- **Summary of Changes**:
  - Authored [`ADR-014`](./architecture/ADRs/ADR-014-billing-invoicing-tax-credits-and-batch-execution.md) and indexed it in [`ADRs/README.md`](./architecture/ADRs/README.md).
  - Created Flyway [`V6__billing_schema.sql`](../src/main/resources/db/migration/V6__billing_schema.sql) (`billing_runs`, `tax_policies`, `invoices`, `invoice_lines`, `invoice_tax_lines`, `prepaid_credit_grants`, `credit_transactions`, `credit_notes`, `credit_note_lines`, `billing_adjustments`, sequences `invoice_number_seq` & `credit_note_number_seq`, partial unique index `uq_invoices_sub_period_active`, and `3` PostgreSQL immutability triggers), preserving `V1`–`V5` untouched.
  - Updated `billing/package-info.java` (`allowedDependencies = { "shared", "identity :: api", "catalog :: api", "subscriptions :: api", "metering :: api" }`) and added non-breaking `listBillableSubscriptions(Pageable)` to `SubscriptionQueryFacade`, `SubscriptionRepository`, and `SubscriptionQueryService`.
  - Implemented `com.meterflow.billing.api` (`49` public contracts: enums, exceptions, `TaxCalculationStrategy` SPI, immutable DTOs/commands, `InvoiceFinalizedEvent`, `CreditNoteIssuedEvent`, and `6` facades) and `com.meterflow.billing.internal` (`10` entities, `10` repositories, `BillingMapper`, `StandardPercentageTaxStrategy`, `BillingMetrics`, `TaxPolicyService`, `PrepaidCreditService`, `InvoiceManagementService`, `CreditNoteService`, `BillingAdjustmentService`, `BillingBatchJobsConfiguration` using Spring Batch 6 `ChunkOrientedStepBuilder`, `BillingRunService`, and `BillingAuditEventListener`).
  - Added thin customer-isolated `BillingController` (`/api/v1/billing/**` incl. `/runs/{billingRunId}/resume`, `/api/v1/customer/{customerId}/billing/**`), RFC 9457 billing error mappings in `GlobalApiExceptionHandler`, `BillingUnitTests` (`8/8`), and PostgreSQL 17 Testcontainers `BillingModuleIT` (`8/8`), and expanded `ModularityTests` (`1/1`) and `MeterFlowContextAndFlywayIT` (`2/2`).
- **Verification Executed**:
  - `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`01:40 min`, `322` main + `16` test Java sources, `0` compiler warnings):
    - Surefire (`53/53`, `0` failures/errors/skipped): `BillingUnitTests` (`8/8`), `MetricDefinitionUnitTests` (`4/4`), `PricingEngineUnitTests` (`6/6`), `IdentityUnitTests` (`3/3`), `SecurityFoundationTests` (`8/8`), `MeteringUnitTests` (`5/5`), `ModularityTests` (`1/1`), `MoneyUnitTests` (`6/6`), `SharedFoundationUnitTests` (`7/7`), `BillingPeriodAndProrationUnitTests` (`5/5`).
    - Failsafe (`28/28`, `0` failures/errors/skipped): `BillingModuleIT` (`8/8`), `CatalogModuleIT` (`3/3`), `IdentityAndSecurityIT` (`4/4`), `MeterFlowContextAndFlywayIT` (`2/2`), `MeteringModuleIT` (`6/6`), `SubscriptionModuleIT` (`5/5`) on Testcontainers PostgreSQL `17.12` validating `V1`–`V6` and Hibernate `7.2.7.Final` `ddl-auto=validate`.
  - `.\mvnw.cmd -B -Pjava21 clean test-compile` — **`BUILD SUCCESS`** (`12.107 s`, all `322` main + `16` test files compiled cleanly with `--release 21`).
- **Risks / Open Items**:
  - Payment capture/dunning (`FR8`), outbound webhook delivery (`FR9`), notification delivery, full historical invoice simulation comparison (`FR10`), and Thymeleaf billing consoles remain scoped to upcoming deliverables; no Git commit/push or Supabase connection performed.

---

### 2026-11-25 — Metering Engine: Event Ingestion, Idempotency, Aggregation, Rollups & Usage Tracking (`V5`, `ADR-013`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Metering Module: 72h Ingestion, Five Aggregations, <= 1m Estimates & Budgets` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR1`, `FR2`, `FR7`, `QA1`–`QA5`, `C1`, `C5`, `C7`–`C10`
- **Summary of Changes**:
  - Authored [`ADR-013`](./architecture/ADRs/ADR-013-metering-ingestion-idempotency-rollups-and-freshness.md) and indexed it in [`ADRs/README.md`](./architecture/ADRs/README.md).
  - Created Flyway [`V5__metering_schema.sql`](../src/main/resources/db/migration/V5__metering_schema.sql) (`usage_events`, `usage_rollups`, `usage_late_event_records`, `usage_rejected_events`, `usage_threshold_evaluations`), preserving `V1`–`V4` untouched.
  - Updated `metering/package-info.java` (`allowedDependencies = { "shared", "identity :: api", "catalog :: api", "subscriptions :: api" }`) and added non-breaking batched lookup methods (`findCustomersByIds`, `findMetricsByIds`, `findOpenSubscriptionByCustomerId`, `findOpenSubscriptionsByCustomerIds`) to existing facades to prevent N+1 batch validation queries.
  - Implemented `com.meterflow.metering.api` (`32` contracts, DTOs, exceptions, outbox events `LateUsageEventRecordedEvent` & `UsageThresholdReachedEvent`, and `5` facades) and `com.meterflow.metering.internal` (`5` entities, `4` JPA repositories, `MeteringBatchJdbcRepository` PostgreSQL `UNNEST` batch engine, `CanonicalPayloadHasher`, `LateEventPolicyEvaluator`, `RollupBucketAccumulator`, `MeteringMetrics`, `MeteringCacheHelper`, `UsageIngestionService`, `UsageQueryService`, `LateUsageQueryService`, `UsageReconciliationService`, `UsageThresholdService`).
  - Added thin `MeteringController` (operator/admin + `CustomerAccessGuard`-enforced customer-scoped endpoints), RFC 9457 metering error mappings in `GlobalApiExceptionHandler`, reproducible `perf/k6/` scripts (`qa1-one-million-reconciliation.js`, `qa2-throughput-latency.js`, `qa3-restart-recovery.js`, `README.md`), `MeteringUnitTests` (`5/5`), and PostgreSQL 17 Testcontainers `MeteringModuleIT` (`6/6`), and expanded `ModularityTests` (`1/1`) and `MeterFlowContextAndFlywayIT` (`2/2`).
- **Verification Executed**:
  - `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`01:25 min`, `241` main + `14` test Java sources):
    - Surefire (`45/45`, `0` failures/errors/skipped): `MetricDefinitionUnitTests` (`4/4`), `PricingEngineUnitTests` (`6/6`), `IdentityUnitTests` (`3/3`), `SecurityFoundationTests` (`8/8`), `MeteringUnitTests` (`5/5`), `ModularityTests` (`1/1`), `MoneyUnitTests` (`6/6`), `SharedFoundationUnitTests` (`7/7`), `BillingPeriodAndProrationUnitTests` (`5/5`).
    - Failsafe (`20/20`, `0` failures/errors/skipped): `CatalogModuleIT` (`3/3`), `IdentityAndSecurityIT` (`4/4`), `MeterFlowContextAndFlywayIT` (`2/2`), `MeteringModuleIT` (`6/6`, `10.88 s`), `SubscriptionModuleIT` (`5/5`) on Testcontainers PostgreSQL `17.12` validating `V1`–`V5` and Hibernate `7.2.7.Final` `ddl-auto=validate`.
  - `.\mvnw.cmd -B -Pjava21 test-compile` — **`BUILD SUCCESS`** (`2.873 s`).
  - Live PostgreSQL 17 Testcontainers QA benchmark (`MeteringModuleIT`, `20,000` events, `200`-event batches, `4` threads, `3%` duplicates, `2%` late):
    - Direct Service + PostgreSQL 17: **`10,526.5 events/sec`**, `svcP50=66.03 ms`, `svcP95=131.75 ms`, `timerIngestMean=64.19 ms`, `timerRollupMean=6.77 ms`.
    - Full HTTP + Spring Security (`BCrypt`) + PostgreSQL 17: **`4,092.5 events/sec`**, `httpP50=175.58 ms`, `httpP95=267.30 ms` (`~109 ms` delta attributable to stateless per-request HTTP Basic `BCrypt`), **`parity=EXACT`** (`19,400` unique, `400` late, `600` duplicates, `0` double counting).
  - Post-audit hardening verified in the same run: fixed deferred intra-batch follow-up conflict resolution in `UsageIngestionService`, replaced in-memory entity loading on sub-hour query slices with O(1)-memory SQL aggregation (`MeteringBatchJdbcRepository.aggregateMatchedEventSlice`) plus `1,000`-row bounded pagination for ad-hoc filtered queries, reused `ThreadLocal<MessageDigest>` in `CanonicalPayloadHasher`, and added explicit integration assertions for intra-batch DB conflicts and historical `> 72h` duplicate replays.
- **Risks / Open Items**:
  - Host `k6` CLI is not installed on this workstation; `perf/k6/*.js` scripts are versioned and ready for execution on a `k6`-equipped host against Local Docker Compose PostgreSQL.
  - Measured HTTP `p95` (`633.76 ms` for `500`-event batches over stateless HTTP Basic) exceeds the `100 ms` `QA2` target primarily because stateless HTTP Basic runs `BCrypt` (`~60–100 ms` CPU) on every batch request and `500`-event batch serialization under cold JIT; future API-key / bearer token authentication or smaller `100`–`200` event batches will reduce HTTP `p95` below `100 ms`.
  - Invoicing, tax, credits, payments/dunning, webhook delivery, and notification delivery remain out of scope for this task; no Git commit/push or Supabase connection performed.

---

### 2026-11-25 — Subscription Management, Trials, Plan Changes & Proration (`V4`, `ADR-012`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Subscriptions Module: Trials, Mid-Cycle Switches & Proration Segments` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR4`, `FR3` (recurring recurring-vs-usage integration), `QA2`, `QA3`, `QA4`, `C1`, `C5`, `C9`
- **Summary of Changes**:
  - Authored [`ADR-012`](./architecture/ADRs/ADR-012-subscription-workflow-billing-periods-and-proration.md) and indexed it in [`ADRs/README.md`](./architecture/ADRs/README.md).
  - Created Flyway [`V4__subscriptions_schema.sql`](../src/main/resources/db/migration/V4__subscriptions_schema.sql) (`subscriptions`, `subscription_plan_assignments`, `subscription_scheduled_changes`, `subscription_proration_records`, `subscription_state_transitions` with partial unique constraints `uq_subscriptions_one_open_per_customer` and `uq_sub_scheduled_one_pending`), preserving `V1`, `V2`, and `V3` untouched.
  - Added `BillingInterval` and `BillingPeriod` (`ZonedDateTime` calendar clamping for Jan 31 -> Feb 28/29, leap years, and DST-safe UTC seconds) to `com.meterflow.shared`, and extended `catalog.api.RatingEngineFacade` with `rateRecurringCharges(RecurringQuoteRequest)` (`PlanRatingComposite`) so fixed recurring charges (`FLAT_FEE`, contracted `PER_SEAT`) are rated cleanly while usage-based charges are explicitly excluded from fixed-fee proration.
  - Implemented `com.meterflow.subscriptions.api` (`SubscriptionManagementFacade`, `SubscriptionQueryFacade`, `SubscriptionMaintenanceFacade`, enums, commands, immutable DTOs, typed exceptions) and `com.meterflow.subscriptions.internal` (`5` entities, `5` repositories with PostgreSQL advisory + `PESSIMISTIC_WRITE` row locks, `ProrationCalculator` `EXACT_SECONDS_V1`, `SubscriptionMapper`, `SubscriptionManagementService`, `SubscriptionQueryService`, `SubscriptionMaintenanceService`, `SubscriptionSchedulingConfiguration`).
  - Added thin `SubscriptionController` (operator/admin + `CustomerAccessGuard`-enforced customer endpoints) and extended `GlobalApiExceptionHandler` with RFC 9457 subscription and optimistic-locking mappings.
  - Added `BillingPeriodAndProrationUnitTests` (`5/5`) and PostgreSQL 17 Testcontainers `SubscriptionModuleIT` (`5/5`), and expanded `ModularityTests` (`1/1`) and `MeterFlowContextAndFlywayIT` (`2/2`).
- **Verification Executed**:
  - `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`01:12 min`, `188` main + `12` test Java sources):
    - Surefire (`40/40`, `0` failures/errors/skipped): `MetricDefinitionUnitTests` (`4/4`, `0.239 s`), `PricingEngineUnitTests` (`6/6`, `1.083 s`), `IdentityUnitTests` (`3/3`, `0.617 s`), `SecurityFoundationTests` (`8/8`, `7.254 s`), `ModularityTests` (`1/1`, `3.233 s`), `MoneyUnitTests` (`6/6`, `0.069 s`), `SharedFoundationUnitTests` (`7/7`, `0.147 s`), `BillingPeriodAndProrationUnitTests` (`5/5`, `0.017 s`).
    - Failsafe (`14/14`, `0` failures/errors/skipped): `CatalogModuleIT` (`3/3`, `20.05 s`), `IdentityAndSecurityIT` (`4/4`, `8.192 s`), `MeterFlowContextAndFlywayIT` (`2/2`, `5.875 s`), `SubscriptionModuleIT` (`5/5`, `8.613 s`) on Testcontainers PostgreSQL `17.12` validating `V1`–`V4` and Hibernate `7.2.7.Final` `ddl-auto=validate`.
- **Risks / Open Items**:
  - Raw usage ingestion/aggregation/live estimates (`FR1`, `FR2`, `FR7`), invoicing/tax/credits (`FR5`, `FR6`), payment/dunning (`FR8`), and webhooks (`FR9`) remain scoped to upcoming deliverables; no Git commit/push or Supabase access performed.

---

### 2026-11-25 — Catalogue Module, Billable Metrics, Immutable Plan Versions & Extensible Pricing Engine (`V3`, `ADR-011`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Catalog Module: Metrics, Immutable Plan Versions, Six Charges & RatingEngine` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR2` (definitions), `FR3`, `FR10` (immutability & read-only simulation foundation), `QA2`, `QA3`, `C1`, `C5`, `C9`
- **Summary of Changes**:
  - Authored [`ADR-011`](./architecture/ADRs/ADR-011-pricing-tiers-packages-allowances-and-commitments.md) documenting tier boundaries (`(lower, upper]`, graduated vs. volume), ceiling partial-package rounding, single-consumption free allowances, non-duplicative commitments, string-keyed OCP strategy SPI, and PostgreSQL published-version immutability triggers.
  - Created Flyway [`V3__catalog_schema.sql`](../src/main/resources/db/migration/V3__catalog_schema.sql) (`billable_metrics`, `metric_filters`, `pricing_plans`, `plan_versions`, `plan_charges`, `charge_tiers`, `free_allowances`, `minimum_commitments`, plus `5` `BEFORE UPDATE/DELETE/INSERT` immutability triggers), preserving `V1` and `V2` untouched.
  - Implemented `com.meterflow.catalog.api` (`MetricCatalogFacade`, `PlanCatalogFacade`, `RatingEngineFacade`, `PricingSimulationFacade`, `ChargeCalculationStrategy` SPI, `MetricEvaluationSupport`, immutable DTOs, typed exceptions) and `com.meterflow.catalog.internal` (`8` entities, `8` batch-fetching repositories, `FlatFeeChargeStrategy`, `PerUnitChargeStrategy`, `GraduatedTiersChargeStrategy`, `VolumeTiersChargeStrategy`, `PackageChargeStrategy`, `PerSeatChargeStrategy`, `ChargeStrategyRegistry`, `PlanRatingComposite`, `MetricCatalogService`, `PlanCatalogService`, `PricingSimulationService`, `CatalogCacheHelper`).
  - Added thin REST controllers (`CatalogMetricController`, `CatalogPlanController`, `PricingSimulationController`, `PageResponse`) enforcing `CustomerAccessGuard` and extended `GlobalApiExceptionHandler` with RFC 9457 catalog error mappings.
  - Added `MetricDefinitionUnitTests` (`4/4`), `PricingEngineUnitTests` (`6/6` including a test-only 7th OCP strategy), and PostgreSQL 17 Testcontainers `CatalogModuleIT` (`3/3`), and expanded `ModularityTests` (`1/1`) and `MeterFlowContextAndFlywayIT` (`2/2`).
- **Verification Executed**:
  - `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`52.667 s`, `144` main + `10` test Java sources):
    - Surefire (`35/35`, `0` failures/errors/skipped): `MetricDefinitionUnitTests` (`4/4`, `0.244 s`), `PricingEngineUnitTests` (`6/6`, `0.952 s`), `IdentityUnitTests` (`3/3`, `0.440 s`), `SecurityFoundationTests` (`8/8`, `5.240 s`), `ModularityTests` (`1/1`, `2.467 s`), `MoneyUnitTests` (`6/6`, `0.059 s`), `SharedFoundationUnitTests` (`7/7`, `0.138 s`).
    - Failsafe (`9/9`, `0` failures/errors/skipped): `CatalogModuleIT` (`3/3`, `17.68 s`), `IdentityAndSecurityIT` (`4/4`, `7.429 s`), `MeterFlowContextAndFlywayIT` (`2/2`, `5.143 s`) on Testcontainers PostgreSQL `17.12` validating `V1`+`V2`+`V3` (`00:00.166s`) and Hibernate `7.2.7.Final` `ddl-auto=validate`.
- **Risks / Open Items**:
  - Raw event ingestion/runtime aggregation (`FR1`/`FR2`/`FR7`), subscription proration (`FR4`), and historical invoice comparison (`FR5`/`FR6`/`FR10`) remain scoped to upcoming `Subscriptions`, `Metering`, and `Billing` modules; no Git commit/push performed per scope.

---

### 2026-11-25 — Shared Financial Kernel (`ADR-010`) & Identity/Security Module (`V2`)

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Shared Financial Kernel & Identity / Security Module` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`, `C5`, `C6`, `C9`, `QA2`, `QA3`, `QA4`
- **Summary of Changes**:
  - Authored [`docs/architecture/ADRs/ADR-010-financial-precision-currency-and-rounding-policy.md`](./architecture/ADRs/ADR-010-financial-precision-currency-and-rounding-policy.md) formalizing `BDT` & `USD` currency support, unrounded intermediate `BigDecimal` arithmetic, explicit `scale=8` / `scale=4` / `scale=2` (paisa/cent `HALF_UP`) rounding, scale-insensitive numerical equality, and plain-decimal JSON serialization.
  - Upgraded canonical [`Money.java`](../src/main/java/com/meterflow/shared/Money.java) (immutable `final class`, zero `float`/`double`, `@JsonAutoDetect` restricting JSON strictly to `amount` & `currency`) and added `SupportedCurrency`, `FinancialException`, `InvalidCurrencyException`, `CurrencyMismatchException`, `InvalidMoneyException`, and `CorrelationIds` in `com.meterflow.shared`.
  - Created Flyway [`V2__identity_schema.sql`](../src/main/resources/db/migration/V2__identity_schema.sql) (`customers`, `users`, `customer_memberships` with UUID PKs, FK/UNIQUE/CHECK constraints, optimistic locking `version`, UTC `TIMESTAMPTZ`, partial active-membership indexes), leaving `V1` untouched.
  - Implemented encapsulated `identity` module: `identity.api` (`MeterFlowRoles`, `PlatformRole`, `UserStatus`, `CustomerStatus`, `MembershipRole`, `MembershipStatus`, DTOs, typed exceptions, `IdentityManagementFacade`, `IdentityQueryFacade`, `CustomerAccessGuard`) and `identity.internal` (`CustomerEntity`, `UserEntity`, `CustomerMembershipEntity`, repositories, `IdentityService` with PostgreSQL microsecond UTC truncation, `DatabaseUserDetailsService`, `DefaultCustomerAccessGuard`, one-time `InitialAdminBootstrapRunner`, hardened `SecurityConfiguration`).
  - Removed hardcoded local/test in-memory bootstrap credentials from `application-local.yml` and `application-test.yml`, updated `.env.example` with placeholder-only `METERFLOW_BOOTSTRAP_ADMIN_*` variables, and unified RFC 9457 ProblemDetail handling in [`GlobalApiExceptionHandler.java`](../src/main/java/com/meterflow/portal/internal/GlobalApiExceptionHandler.java).
  - Added `MoneyUnitTests` (`6/6`), `IdentityUnitTests` (`3/3`), expanded `SecurityFoundationTests` (`8/8`), `ModularityTests` (`1/1`), `MeterFlowContextAndFlywayIT` (`2/2`), and PostgreSQL Testcontainers `IdentityAndSecurityIT` (`4/4`).
- **Verification Executed**:
  - Baseline pre-change check: `.\mvnw.cmd -B verify` (`BUILD SUCCESS`, `30.484 s`, `17/17`).
  - Post-implementation full check: `.\mvnw.cmd -B clean verify` — **`BUILD SUCCESS`** (`41.005 s`):
    - Surefire (`25/25`, `0` failures/errors/skipped): `IdentityUnitTests` (`3/3`, `1.285 s`), `SecurityFoundationTests` (`8/8`, `4.270 s`), `ModularityTests` (`1/1`, `2.287 s`), `MoneyUnitTests` (`6/6`, `0.070 s`), `SharedFoundationUnitTests` (`7/7`, `0.140 s`).
    - Failsafe (`6/6`, `0` failures/errors/skipped): `IdentityAndSecurityIT` (`4/4`, `17.20 s`) and `MeterFlowContextAndFlywayIT` (`2/2`, `5.302 s`) on Testcontainers PostgreSQL `17.12` validating `V1` + `V2` (`00:00.074s`–`00:00.089s`) and Hibernate `7.2.7.Final` `ddl-auto=validate`.
- **Risks / Open Items**:
  - `BillingPeriod` and `ProrationCalculator` remain scoped to upcoming `Catalog`/`Subscriptions` work; zero `FR1`–`FR10` billing domain logic implemented yet; no Git commit/push performed per task scope.

---

### 2026-11-25 — Full Docker Compose Runtime Smoke Test, JVM Memory Tuning & Git Baseline Commit

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Build System, Local PostgreSQL, Flyway & CI Baseline` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`, `C5`–`C9`, `C11`, `QA1`–`QA5`
- **Summary of Changes**:
  - Executed full containerized runtime smoke test via `docker compose config -q`, `docker compose --profile full config -q`, and `docker compose --profile full up --build -d` (using `SERVER_PORT=18080` on the Windows host because Hyper-V/WinNAT dynamically excluded TCP ports `8047–8146`, while `meterflow-app` listens internally on port `8080`).
  - Caught high idle container RSS (`502.8 MiB / 512 MiB`, `98.20%`) on initial `meterflow-app` startup caused by `-XX:MaxRAMPercentage=75.0 -XX:+UseZGC` in [`Dockerfile`](../Dockerfile); updated `JAVA_OPTS` to `-XX:MaxRAMPercentage=50.0 -XX:+UseG1GC` and rebuilt the image cleanly.
  - Completed final infrastructure audit across `pom.xml`, `compose.yaml`, `Dockerfile`, `application*.yml`, `V1__framework_outbox_and_batch_schema.sql`, `SecurityConfiguration`, `BatchInfrastructureConfiguration`, `OutboxRetryScheduler`, `SchedulingConfiguration`, and `MeterFlowContextAndFlywayIT`: zero duplicate config, zero banned dependencies, zero hardcoded production secrets, zero `.env` or `target/` tracked artifacts.
  - Verified Git identity (`arman07k`), clean `.git/config`, `git diff --cached --check`, and staged the complete verified foundation for the initial local commit (`chore: establish MeterFlow infrastructure foundation`; no remote push).
- **Verification Executed**:
  - `docker compose config -q` & `docker compose --profile full config -q`: exit `0`.
  - `docker compose --profile full up --build -d` (with `SERVER_PORT=18080`): `meterflow-postgres` `Up (healthy)` (`Restarts=0`) and `meterflow-app` `Up (healthy)` (`Restarts=0`).
  - Actuator probes (`http://127.0.0.1:18080`): `/actuator/health` (`HTTP 200`, `{"groups":["liveness","readiness"],"status":"UP"}`), `/actuator/health/readiness` (`HTTP 200`, `{"status":"UP"}`), `/actuator/health/liveness` (`HTTP 200`, `{"status":"UP"}`), `/actuator/info` (`HTTP 200`, `MeterFlow 0.1.0-SNAPSHOT`).
  - `docker stats --no-stream meterflow-postgres meterflow-app`: `meterflow-postgres` `41.89 MiB / 512 MiB` (`8.18%`, `4.91%` CPU) + `meterflow-app` `276.8 MiB / 512 MiB` (`54.05%`, `0.22%` CPU) = `318.69 MiB` combined (`< 4%` of 8 GB RAM).
  - Container logs & PostgreSQL `flyway_schema_history`: Flyway `V1` validated (`00:00.040s`, `success = t`), Hibernate `7.2.7.Final` `ddl-auto=validate` passed, zero critical startup exceptions.
- **Risks / Open Items**:
  - Host port `8080` may be excluded by Windows Hyper-V/WinNAT dynamic port ranges (`netsh interface ipv4 show excludedportrange protocol=tcp`); use `SERVER_PORT=18080` (or another non-excluded port) when running Compose on Windows.
  - `supabase` profile intentionally not connected; ready to start `Shared Financial Kernel & Identity / Security Module`.

---

### 2026-11-25 — Outbox Scheduler Registration Fix & Full Maven/Testcontainers/Local Verification

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Build System, Local PostgreSQL, Flyway & CI Baseline` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`, `C5`–`C9`, `C11`, `QA2`–`QA5`
- **Summary of Changes**:
  - Diagnosed `MeterFlowContextAndFlywayIT` failure (`NoSuchBeanDefinitionException: OutboxRetryScheduler`): `@ConditionalOnBean(IncompleteEventPublications.class)` on component-scanned `OutboxRetryScheduler` evaluated before Spring Modulith's `EventPublicationAutoConfiguration` registered `IncompleteEventPublications`. Also identified via `javap` on `spring-modulith-moments-2.0.4.jar` that `MomentsAutoConfiguration` activated `@EnableScheduling` by default even when `meterflow.scheduling.enabled=false`, and that `@Testcontainers`/`@Container` stopped PostgreSQL before Spring closed `HikariDataSource`.
  - Updated [`src/main/java/com/meterflow/shared/OutboxRetryScheduler.java`](../src/main/java/com/meterflow/shared/OutboxRetryScheduler.java): registered unconditionally as `@Component` (constructor-injected `IncompleteEventPublications`), removed `@ConditionalOnBean` and `@Scheduled`, exposed `minimumAge()`.
  - Updated [`src/main/java/com/meterflow/shared/SchedulingConfiguration.java`](../src/main/java/com/meterflow/shared/SchedulingConfiguration.java): implemented `SchedulingConfigurer` to register the `15s` fixed-delay outbox retry task only when `meterflow.scheduling.enabled=true` (`local`/`supabase`).
  - Updated [`src/main/resources/application.yml`](../src/main/resources/application.yml): explicitly set `spring.modulith.moments.enabled: false` and `meterflow.scheduling.enabled: true`.
  - Updated [`src/test/java/com/meterflow/MeterFlowContextAndFlywayIT.java`](../src/test/java/com/meterflow/MeterFlowContextAndFlywayIT.java): switched to Spring-managed `@TestConfiguration` `@Bean @ServiceConnection PostgreSQLContainer`, added live `outboxRetryScheduler.resubmitIncompleteEvents()` check and assertions that `SchedulingConfiguration` and `ScheduledTaskHolder` are absent in `test`.
  - Updated [`src/test/java/com/meterflow/shared/SharedFoundationUnitTests.java`](../src/test/java/com/meterflow/shared/SharedFoundationUnitTests.java): added `ApplicationContextRunner` test proving scheduling activates (`1` task) when `meterflow.scheduling.enabled=true` and stays disabled (`0` tasks, `OutboxRetryScheduler` still present) when `false`.
- **Verification Executed**:
  - Host toolchain verified: Temurin JDK `25.0.5.1+2-LTS` (`JAVA_HOME`), Docker `29.9.3`, Docker Compose `v5.7.1`, `meterflow-postgres` (`postgres:17-alpine`, PostgreSQL `17.12`) `Up (healthy)`.
  - `.\mvnw.cmd -B clean verify`: **BUILD SUCCESS** (`30.985 s`) — Surefire `15/15` (`SecurityFoundationTests` `7/7`, `ModularityTests` `1/1`, `SharedFoundationUnitTests` `7/7`, `0` failures/errors/skipped); Failsafe `2/2` (`MeterFlowContextAndFlywayIT` on Testcontainers `postgres:17-alpine`, `0` failures/errors/skipped, Flyway `V1` applied in `00:00.062s`, Hibernate `ddl-auto=validate` passed, clean JVM shutdown with zero Hikari warnings).
  - `.\mvnw.cmd -B -Pjava21 test-compile`: **BUILD SUCCESS** (`2.626 s`).
  - `docker compose config -q`: exit `0`; live `local` profile JAR boot (`-Xmx256m`, port `18080`) against `meterflow-postgres`: Flyway `V1` applied (`00:00.078s`, `8` tables), started in `8.552 s` (`330.9 MB` working set), `/actuator/health/readiness` returned `HTTP 200` `{"status":"UP"}`.
- **Risks / Open Items**:
  - Build/PostgreSQL/Flyway/CI baseline is now **Completed** (uncommitted in working tree); `supabase` profile runtime remains unexecuted by design until Supabase credentials are provisioned; all `FR1`–`FR10` domain business logic remains `Not Started`.

---

### 2026-11-25 — Infrastructure Quality Audit & Local Environment Verification Check

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Build System, Local PostgreSQL, Flyway & CI Baseline` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`, `C5`–`C9`, `C11`, `QA2`–`QA5`
- **Summary of Changes**:
  - Audited all generated files against the 12-point Infrastructure Quality Audit (duplicate config, redundant deps, unnecessary abstractions, profile inheritance, management endpoints, hardcoded secrets, non-root Docker, PostgreSQL healthcheck, Flyway, Spring Batch schema, Spring Modulith event registry, memory consumption).
  - Inspected `META-INF/spring-configuration-metadata.json` directly inside `spring-boot-batch-4.0.5.jar`, `spring-boot-batch-jdbc-4.0.5.jar`, and `spring-modulith-events-core-2.0.4.jar`.
  - Fixed [`src/main/resources/application.yml`](../src/main/resources/application.yml): replaced dead `jdbc-schema-initialization` key with verified Spring Modulith `2.0.4` properties (`completion-mode: UPDATE`, `republish-outstanding-events-on-restart: true`, `externalization.enabled: false`).
  - Fixed [`compose.yaml`](../compose.yaml): added `-h 127.0.0.1` to `pg_isready` (preventing first-boot Unix-socket race), added `mem_limit: 512m` on `postgres`, and pointed `app` healthcheck to `/actuator/health/readiness`.
  - Fixed [`pom.xml`](../pom.xml): removed unused `thymeleaf-extras-springsecurity6` and added `-XX:+EnableDynamicAgentLoading` to Surefire and Failsafe plugins.
- **Verification Executed**:
  - Registry & WSL2 toolchain check: `java`, `mvn`, and `docker` absent from Windows Machine/User `PATH`; Docker absent on Windows and WSL2 Ubuntu; user-scope Temurin JDK `25.0.5.1+2` & Maven `3.9.9` verified runnable via explicit session `JAVA_HOME`.
  - `.\mvnw.cmd -B clean -Pjava21 test-compile`: **VERIFIED PASS** (`32` main + `4` test sources in `5.277 s`).
  - `.\mvnw.cmd -B clean test package dependency:tree`: **VERIFIED PASS** (`14/14` tests: `SecurityFoundationTests` `7/7`, `ModularityTests` `1/1`, `SharedFoundationUnitTests` `6/6`; zero Mockito warnings; `target/meterflow-0.1.0-SNAPSHOT.jar` built in `17.180 s`).
  - **BLOCKED (Docker Desktop / Docker Engine not installed)**: `docker compose up`, PostgreSQL container health, Testcontainers `MeterFlowContextAndFlywayIT`, and live PostgreSQL-backed `/actuator/health/readiness`.
- **Risks / Open Items**:
  - Infrastructure acceptance criteria are **not yet fully satisfied** until Docker is installed/started and `docker compose up -d postgres` + `./mvnw -B verify` + live `local` app startup pass against PostgreSQL 17.

---

### 2026-11-25 — OpenAPI 3, Spring Batch 6 Executor, Outbox Sweeper, Observability & Config Audit

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Build System, Local PostgreSQL, Flyway & CI Baseline` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`, `C5`–`C9`, `C11`, `QA2`–`QA5`
- **Summary of Changes**:
  - Verified exact workstation toolchain: `java`, `mvn`, and `docker` are absent from system `PATH`; user-scope Temurin JDK `25.0.5.1+2` and Maven `3.9.9` exist under local `.tools`; complete `only-script` Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) is present.
  - Added `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.0`, `OpenApiConfiguration`, `BatchInfrastructureConfiguration` (`meterflowBatchTaskExecutor` bounded per profile: `4` local / `1` supabase), `OutboxRetryScheduler`, `GlobalApiExceptionHandler` (RFC 9457 `ProblemDetail` + `correlationId`), Actuator probes (`liveness`/`readiness`), and `info.app` metadata.
  - Audited all generated files: removed duplicated properties from `application-test.yml`, restricted `/v3/api-docs/**` and `/swagger-ui/**` to `ADMIN`/`OPERATOR`, activated `@ActiveProfiles("test")` in `SecurityFoundationTests` (eliminating generated security password warning), and expanded `MeterFlowContextAndFlywayIT` assertions to cover `JobRepository`, `JobOperator`, `OutboxRetryScheduler`, and `OpenAPI`.
- **Verification Executed**:
  - `.\mvnw.cmd -B clean -Pjava21 test-compile`: **PASSED** (`32` main + `4` test sources in `4.787 s`).
  - `.\mvnw.cmd -B clean test package dependency:tree`: **PASSED** (`14/14` tests: `SecurityFoundationTests` `7/7`, `ModularityTests` `1/1`, `SharedFoundationUnitTests` `6/6`; executable JAR repackaged in `17.358 s`).
  - Banned dependency scan & forbidden tracking-label scan: **PASSED** (`0` hits).
  - Blocked locally: `docker compose config`/`up` and `MeterFlowContextAndFlywayIT` (`Testcontainers`) remain blocked locally because Docker is not installed on this machine.
- **Risks / Open Items**:
  - Add JDK 25 + Maven to user/system `PATH` and install Docker Desktop / Engine locally (or push to GitHub Actions) to execute `docker compose up` and `./mvnw -B verify`.

---

### 2026-11-25 — Build, Flyway V1, Modulith Skeleton, Security Foundation & CI Baseline

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Build System, Local PostgreSQL, Flyway & CI Baseline` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`, `C5`, `C6`, `C7`, `C8`, `C9`, `C11`, `QA2`, `QA3`, `QA4`, `QA5`
- **Summary of Changes**:
  - Installed user-scope Eclipse Temurin JDK `25.0.5.1+2-LTS` and Apache Maven `3.9.9` per stakeholder approval, and generated Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
  - Created `.gitignore`, `.env.example`, `compose.yaml`, `Dockerfile`, `pom.xml`, and `.github/workflows/ci.yml`.
  - Created `application.yml`, `application-local.yml`, `application-supabase.yml`, `application-test.yml`, and `V1__framework_outbox_and_batch_schema.sql` (verified against `spring-batch-core-6.0.3.jar` `BATCH_JOB_INSTANCE_SEQ` and `spring-modulith-events-jpa-2.0.4.jar` `JpaEventPublication`).
  - Created `MeterFlowApplication`, all `10` Spring Modulith module boundary `package-info.java` + `@NamedInterface("api")` declarations, `shared` foundation beans (`Money`, `ClockConfiguration`, `CacheNames`, `CaffeineCacheConfiguration`, `SchedulingConfiguration`), dual Spring Security 7 filter chains (`SecurityConfiguration`), `CorrelationIdFilter`, minimal Thymeleaf views (`index.html`, `login.html`), and foundation tests (`ModularityTests`, `SharedFoundationUnitTests`, `SecurityFoundationTests`, `MeterFlowContextAndFlywayIT`).
  - Confirmed strictly zero business logic (`FR1`–`FR10`), zero Supabase connections, zero deployments, and zero Git commits.
- **Verification Executed**:
  - `.\mvnw.cmd -B clean -Pjava21 test-compile`: **PASSED** (clean Java 21 fallback cross-compilation).
  - `.\mvnw.cmd -B clean test package`: **PASSED** (`10/10` tests: `ModularityTests` `1/1`, `SharedFoundationUnitTests` `3/3`, `SecurityFoundationTests` `6/6`; executable JAR `target/meterflow-0.1.0-SNAPSHOT.jar` `74,562,328` bytes).
  - `target/dependency-tree.txt` scan: **PASSED** (`0` Redis, Kafka, RabbitMQ, Stripe, PayPal, or H2 dependencies).
  - Static terminology & secret audit across `44` code/build/config files: **PASSED** (`0` forbidden tracking labels or secrets).
  - Docker Compose & Testcontainers (`MeterFlowContextAndFlywayIT`): **NOT EXECUTED** locally because Docker is not installed on this workstation (compiles cleanly; configured for GitHub Actions CI).
- **Risks / Open Items**:
  - Live `docker compose up` and `./mvnw -B verify` (`MeterFlowContextAndFlywayIT`) must run once Docker Desktop / Docker Engine is available locally or in GitHub Actions.

---

### 2026-11-25 — Independent Review Corrections (`FR1`, `FR3`, `FR7`, `FR8`) & Complete `FR9`/`FR10` Audit

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Documentation & Architecture Foundation` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR1`, `FR3`, `FR7`, `FR8`, `FR9`, `FR10`, `QA1`–`QA5`, `C1`–`C12`
- **Summary of Changes**:
  - Corrected **`FR3`** across all specifications and ADRs to the exact six mandatory charge types (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, **`PER_SEAT`**), added `seat_quantity` to `subscriptions`/`subscription_segments`, documented `ChargeCalculationStrategy` + `PlanRatingComposite` (OCP extensibility for future types such as optional `PERCENTAGE`), and retained `0` duplicate pricing logic.
  - Corrected **`FR8`** (`DATA_MODEL.md`, `SYSTEM_DESIGN.md`, `ADR-007`) to enforce **exact three payment retries** (`retry_offsets_hours = [24, 72, 168]` — Day 1, Day 3, Day 7) strictly separated from `suspension_offset_hours` / `suspension_at`, documenting customer notifications at every transition and payment recovery at any applicable point.
  - Proved **`FR7`** one-minute freshness mathematically (`SYSTEM_DESIGN.md`, `ADR-003`, `QUALITY_ATTRIBUTE_PLAN.md`): synchronous in-TX `bucket_hour` `usage_rollups` (`0s` outbox/aggregation lag) + after-commit eviction + `15s` Caffeine `liveEstimates` TTL ceiling (`0.70s` typical / `15.65s` worst-case `<< 60s`), while isolating authoritative billing from Caffeine caches.
  - Clarified **`FR1`** idempotency identity (`DATA_MODEL.md`, `REQUIREMENTS_TRACEABILITY.md`, `SYSTEM_DESIGN.md`): standardized explicit `(customer_id, idempotency_key)` + canonical `SHA-256` `payload_hash` (`200 DUPLICATE_IGNORED` vs. `409 IDEMPOTENCY_KEY_CONFLICT`), executing idempotency lookup before the `72h` expiration check and preserving `1,000`-event partial-acceptance batch ingestion.
  - Completed deep audit of **`FR9`** and **`FR10`** (`ADR-009`, `REQUIREMENTS_TRACEABILITY.md`, `DATA_MODEL.md`): added exact mandatory webhook event names (`invoice.finalised`, `payment.failed`, `usage.threshold_reached`), dual-key encrypted secret rotation (`active_signing_secret_enc` + `previous_signing_secret_enc` + `previous_secret_expires_at`), constant-time `<= 300s` verification, RBAC-scoped manual replay with fresh timestamp signing, and `@Transactional(readOnly = true)` `FR10` invoice simulation authorization.
- **Verification Executed**:
  - Executed automated PowerShell audit confirming all `25` Markdown files, `0` implementation/migration/config files, `100%` valid portable relative links, `0` stale `transaction_id` or `[24, 72, 168, 336]` retry errors, `100%` presence of `PER_SEAT`/`GRADUATED_TIERS`/`VOLUME_TIERS`/`invoice.finalised`/`payment.failed`/`usage.threshold_reached`/`payload_hash`, `9/9` complete ADRs, `10/10` valid Mermaid diagrams, and `0` forbidden tracking labels.
- **Risks / Open Items**:
  - Runtime implementation remains `Not Started` (`0%` code); ready to proceed to `Build System, Local PostgreSQL, Flyway & CI Baseline`.

---

### 2026-11-25 — Comprehensive `FR1`–`FR10` Architecture, Financial Correctness & Portability Audit

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Documentation & Architecture Foundation` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`
- **Summary of Changes**:
  - Audited all `24` initial Markdown files against the complete `FR1`–`FR10` SaaS usage-based metering and billing requirements, `QA1`–`QA5` targets, `C1`–`C12` common engineering rules, and cross-platform link portability.
  - Resolved the primary pre-audit domain discrepancy (where initial paste truncation had produced temporary utility-meter placeholders) by updating [`docs/PROJECT_CHARTER.md`](./PROJECT_CHARTER.md), [`docs/REQUIREMENTS_TRACEABILITY.md`](./REQUIREMENTS_TRACEABILITY.md), [`docs/architecture/SYSTEM_DESIGN.md`](./architecture/SYSTEM_DESIGN.md), [`docs/architecture/MODULE_BOUNDARIES.md`](./architecture/MODULE_BOUNDARIES.md), [`docs/architecture/DATA_MODEL.md`](./architecture/DATA_MODEL.md), [`docs/architecture/QUALITY_ATTRIBUTE_PLAN.md`](./architecture/QUALITY_ATTRIBUTE_PLAN.md), [`docs/architecture/UTILITY_TREE.md`](./architecture/UTILITY_TREE.md), [`docs/architecture/PATTERN_JUSTIFICATION.md`](./architecture/PATTERN_JUSTIFICATION.md), [`docs/architecture/IMPLEMENTATION_CHECKLIST.md`](./architecture/IMPLEMENTATION_CHECKLIST.md), and `ADR-001`–`ADR-008` to model all `10` acyclic modules (`shared`, `identity`, `catalog`, `subscriptions`, `metering`, `billing`, `payments`, `webhooks`, `notifications`, `portal`).
  - Specified `NUMERIC(19,8)` intermediate proration/percentage math rounding `HALF_UP` to `NUMERIC(19,4)` line amounts, cumulative-delta re-rating for post-finalization `<= 72h` late events (`FR1`, `FR6`), one canonical `RatingEngine` across `FR3`/`FR5`/`FR6`/`FR7`/`FR10`, `30s`-TTL after-commit Caffeine `liveEstimates` (`<= 1m` freshness, `FR7`), two-step Spring Batch `DRAFT`/`FINALIZE` invoicing (`FR5`), and `Clock`-testable dunning retry schedules (`FR8`).
  - Added [`docs/architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md`](./architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) isolating `FR9` `HMAC-SHA256` outbound webhooks, exponential backoff, attempt history, and replay into a PostgreSQL `FOR UPDATE SKIP LOCKED` queue so slow third-party HTTP endpoints never block internal `event_publication` outbox threads.
  - Removed workstation-specific directory paths from [`MEMORY.md`](../MEMORY.md) and this log so `100%` of repository links and path references are portable across machines; synchronized [`AGENT.md`](../AGENT.md), [`MEMORY.md`](../MEMORY.md), [`PLAN.md`](../PLAN.md), [`CHANGELOG.md`](../CHANGELOG.md), and [`docs/AI_USAGE_LOG.md`](./AI_USAGE_LOG.md).
- **Verification Executed**:
  - Executed automated PowerShell audit verifying existence of all `25` Markdown files, `0` non-Markdown implementation files, `0` local-filesystem URI schemes or workstation-specific paths, `100%` valid relative Markdown links, `27/27` requirement IDs (`FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`), `7/7` required sections across all `9` ADRs, valid Mermaid syntax across all `10` diagrams, `0` prohibited tracking terms or banned technologies, and clean `git status`.
- **Risks / Open Items**:
  - Zero application code exists yet (`Not Started` across all runtime deliverables). Local JDK 25 vs. JDK 21 check and untracked Supabase Session-Mode (`5432`) environment variables remain prerequisites for the upcoming build baseline deliverable.

---

### 2026-11-24 — Initial Repository Clone & Documentation Foundation Scaffold

- **Contributor(s)**: AI Engineering Agent (supervised by Repository Owner)
- **Deliverable Target**: `Documentation & Architecture Foundation` ([`PLAN.md`](../PLAN.md))
- **Requirement IDs**: `C1`–`C4`, `C12`
- **Summary of Changes**:
  - Cloned empty `https://github.com/arman07k/MeterFlow.git` (`main` branch) into repository root (`./`), stripped embedded token from `.git/config`, and scaffolded the initial `24` governance, charter, traceability, architecture, and ADR Markdown files while flagging upstream `FR1`–`FR10` specification wording for audit alignment.
- **Verification Executed**:
  - Verified clean `.git/config`, file existence, Mermaid syntax, and zero code/database/commit side effects.
- **Risks / Open Items**:
  - Upstream `FR1`–`FR10` specification alignment subsequently completed during the `2026-11-25` audit above.
