# MeterFlow Implementation Checklist (`docs/architecture/IMPLEMENTATION_CHECKLIST.md`)

## 1. Checklist Rules

This checklist tracks concrete deliverables across **architecture documentation, build/CI infrastructure, the ten Spring Modulith modules, and automated verification suites** (`FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`).

- Check `[x]` **only** when artifacts exist on disk and pass verification.
- Currently, **all implementation sections (`§2`, `§3`, `§4.1`–`§4.7`, and `§5` automated unit/Modulith/Testcontainers/UI/QA benchmark suites)** are verified (`[x]`, `100/100` tests green, Java 25 & Java 21 clean build, Docker Compose full stack healthy). Optional host `k6` CLI execution remains unchecked (`[ ]`) solely because `k6` is not installed on the local workstation (`perf/k6/*.js` authored; `QaEngineeringValidationIT` executed and verified).

---

## 2. Documentation & Architecture Foundation (`C1`–`C4`, `C12`)

- [x] Establish portable [`AGENT.md`](../../AGENT.md), [`MEMORY.md`](../../MEMORY.md), [`PLAN.md`](../../PLAN.md), [`CHANGELOG.md`](../../CHANGELOG.md), [`docs/WORK_LOG.md`](../WORK_LOG.md), and [`docs/AI_USAGE_LOG.md`](../AI_USAGE_LOG.md).
- [x] Align [`docs/PROJECT_CHARTER.md`](../PROJECT_CHARTER.md) and [`docs/REQUIREMENTS_TRACEABILITY.md`](../REQUIREMENTS_TRACEABILITY.md) across `FR1`–`FR10`, `QA1`–`QA5`, and `C1`–`C12`.
- [x] Align [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md), [`MODULE_BOUNDARIES.md`](./MODULE_BOUNDARIES.md), [`DATA_MODEL.md`](./DATA_MODEL.md), [`QUALITY_ATTRIBUTE_PLAN.md`](./QUALITY_ATTRIBUTE_PLAN.md), [`UTILITY_TREE.md`](./UTILITY_TREE.md), [`PATTERN_JUSTIFICATION.md`](./PATTERN_JUSTIFICATION.md), and this checklist.
- [x] Establish [`ADRs/README.md`](./ADRs/README.md) and fifteen accepted ADRs (`ADR-001` through `ADR-015`).

---

## 3. Build System, Local PostgreSQL, Flyway & CI Baseline (`C1`, `C5`–`C7`, `C11`, `QA3`, `QA5`)

- [x] Create `.gitignore` and `.env.example` (`SUPABASE_DB_*`, `WEBHOOK_SECRET_ENCRYPTION_KEY`; zero committed secrets) (`C6`).
- [x] Create `compose.yaml` (`postgres:17-alpine`, `pg_isready -h 127.0.0.1`, `512m` caps, `/actuator/health/readiness` probe) and non-root `Dockerfile` (`USER meterflow`, `-XX:MaxRAMPercentage=50.0 -XX:+UseG1GC`) (`C5`, `C11`).
- [x] Scaffold complete Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`) + `pom.xml` targeting Java 25 (`-Pjava21` verified), Spring Boot `4.0.5`, Spring Modulith `2.0.4`, Spring Data JPA `4.0.4`, Flyway `11.14.1`, Spring Batch `6.0.3`, Spring Security `7.0.4`, Caffeine `3.2.3`, SpringDoc OpenAPI `3.0.0`, Spring MVC + Thymeleaf, Validation, Actuator, JUnit `6.0.3`, Mockito `5.20.0`, and Testcontainers `2.0.4` (`0` banned/redundant deps) (`C1`, `C7`, `C9`).
- [x] Configure `application.yml` (`ddl-auto=validate`, UTC, Virtual Threads, `5` Caffeine regions, `spring.batch.jdbc.initialize-schema=never`, verified Spring Modulith `2.0.4` `completion-mode=UPDATE` & `externalization.enabled=false`, Actuator liveness/readiness probes), `application-local.yml` (HikariCP `20`, batch concurrency `4`), `application-supabase.yml` (Session Mode `5432`, HikariCP `6`, batch concurrency `1`), and minimal `application-test.yml` (`C5`, `QA1`).
- [x] Add `V1__framework_outbox_and_batch_schema.sql` (`event_publication` + `BATCH_*` + `BATCH_JOB_INSTANCE_SEQ`, verified against resolved JAR schemas) (`C5`, `C8`).
- [x] Add `MeterFlowApplication`, all `10` module boundaries, `BatchInfrastructureConfiguration`, `OutboxRetryScheduler`, `SchedulingConfiguration`, `OpenApiConfiguration`, `GlobalApiExceptionHandler`, passing `ModularityTests` (`1/1`), `SharedFoundationUnitTests` (`7/7`), `SecurityFoundationTests` (`7/7`), `MeterFlowContextAndFlywayIT` (`2/2`), and `.github/workflows/ci.yml` (`C1`, `C11`).
- [x] Verified `docker compose config -q`, `docker compose --profile full up --build -d` (healthy `meterflow-postgres` `41.89 MiB / 512 MiB` and `meterflow-app` `276.8 MiB / 512 MiB`, `Restarts=0`), full `.\mvnw.cmd -B clean verify` (`15/15` Surefire + `2/2` Failsafe Testcontainers Flyway `V1` + Hibernate `validate` + Batch + Modulith), and containerized `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`, and `/actuator/info` `HTTP 200` (`UP`) (`C5`, `C9`, `C11`).

---

## 4. Spring Modulith Modules (`FR1`–`FR10`)

### 4.1 `shared` Financial Kernel & `identity` (`C1`, `C5`, `C6`, `QA2`, `QA4`, `ADR-010`)
- [x] Implement canonical immutable `shared.Money` & `SupportedCurrency` (`BDT`/`USD`, exact intermediate `BigDecimal`, explicit `scale=8`/`4`/`2` paisa/cent `HALF_UP`, scale-insensitive equality, plain-decimal JSON, typed exceptions) and injectable UTC `Clock` (`MoneyUnitTests` `6/6` PASS).
- [x] Add `V2__identity_schema.sql` (`customers`, `users`, `customer_memberships`), encapsulated `identity` module (`IdentityManagementFacade`, `IdentityQueryFacade`, `CustomerAccessGuard`, `DatabaseUserDetailsService`, one-time `InitialAdminBootstrapRunner`), hardened dual Spring Security 7 chains (`BCrypt(12)`, defensive headers, session fixation migration, CSRF logout, RFC 9457 ProblemDetail errors), and unified `GlobalApiExceptionHandler` (`IdentityUnitTests` `3/3`, `SecurityFoundationTests` `8/8`, `IdentityAndSecurityIT` `4/4` PASS).
- [x] Add `BillingInterval`, `BillingPeriod` (`shared`), and `ProrationCalculator` (`NUMERIC(19,8)` exact-seconds UTC `EXACT_SECONDS_V1`) (`BillingPeriodAndProrationUnitTests` `5/5` PASS) (`FR4`, `ADR-012`).

### 4.2 `catalog` — Metrics, Strategy/Composite Six Mandatory Charge Types & Immutable Plan Versions (`FR2`, `FR3`, `FR10`, `ADR-011`)
- [x] Add `V3__catalog_schema.sql` (`billable_metrics`, `metric_filters`, `pricing_plans`, `plan_versions`, `plan_charges`, `charge_tiers`, `free_allowances`, `minimum_commitments`, plus `5` PostgreSQL `BEFORE UPDATE/DELETE/INSERT` immutability triggers protecting `PUBLISHED` `plan_versions`).
- [x] Implement `BillableMetricDefinition` (`SUM`, `COUNT`, `MAXIMUM`, `UNIQUE_COUNT`, `LATEST_VALUE` with deterministic unsigned UUID tie-breaking) and configurable `JSONB` property filters (`EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `EXISTS`) with explicit missing/null/invalid numeric rejection (`MetricDefinitionUnitTests` `4/4` PASS) (`FR2`).
- [x] Implement immutable `PlanVersion` publishing (`DRAFT` -> `PUBLISHED`), cloning into independent versions, pessimistic concurrent version locking, Caffeine `planVersions`/`metricDefinitions` caching with after-commit eviction, and read-only `PricingSimulationFacade` (`CatalogModuleIT` `3/3` PASS) (`FR10`).
- [x] Implement `ChargeCalculationStrategy` SPI + `ChargeStrategyRegistry` + `PlanRatingComposite` (`RatingEngineFacade`) with all `6` mandatory strategies (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE` ceiling, `PER_SEAT`), single-pass free allowances, non-duplicative charge/plan minimum commitments, thin REST APIs (`CatalogMetricController`, `CatalogPlanController`, `PricingSimulationController`), and test-only 7th strategy OCP proof (`PricingEngineUnitTests` `6/6` PASS) (`FR3`, `QA3`).

### 4.3 `subscriptions` — Trials, Seat Quantities, Mid-Cycle Switches & Proration (`FR3`, `FR4`, `ADR-012`)
- [x] Add `V4__subscriptions_schema.sql` (`subscriptions`, `subscription_plan_assignments`, `subscription_scheduled_changes`, `subscription_proration_records`, `subscription_state_transitions` with single-open & single-pending partial unique indexes).
- [x] Implement authoritative transitions (`TRIALING`, `ACTIVE`, `PAUSED`, `PAST_DUE`, `CANCELLED`, `EXPIRED`), trials (`EXPIRE_ON_END` / `AUTO_CONVERT_TO_PAID`, manual convert/cancel), immediate plan/seat changes with exact-seconds UTC `EXACT_SECONDS_V1` proration via `RatingEngineFacade.rateRecurringCharges` (explicitly excluding usage charges), scheduled period-end changes (conflict guard, authorized replacement, cancellation, idempotent execution), pause/resume period shift, immediate & end-of-period cancellation, customer-isolated `SubscriptionController`, and restart-safe `SubscriptionMaintenanceFacade` (`SubscriptionModuleIT` `5/5` PASS) (`FR4`).

### 4.4 `metering` — Idempotency Key, 72h Gate, Five Aggregations, Proven `<= 1m` Live Estimates & Thresholds (`FR1`, `FR2`, `FR7`, `ADR-013`)
- [x] Add `V5__metering_schema.sql` (`usage_events` with `uq_usage_events_customer_idempotency (customer_id, idempotency_key)`, `payload_hash VARCHAR(64)`, GIN `properties` index; `usage_rollups` keyed by `bucket_start`; `usage_late_event_records`; `usage_rejected_events`; `usage_threshold_evaluations`).
- [x] Implement single & `1,000`-event batch ingestion (`UNNEST ... ON CONFLICT DO NOTHING RETURNING`, partial batch acceptance, idempotency check before `72h` expiry for historical replays, `200` duplicate on matching `payload_hash`, `409` conflict on mismatched `payload_hash`, `422` on `> 72h` or `> +5m` future, `LateUsageEventRecordedEvent` outbox for `<= 72h` closed periods) (`FR1`).
- [x] Implement all `5` aggregation types (`SUM`, `COUNT`, `MAXIMUM`, `UNIQUE_COUNT` exact distinct, `LATEST_VALUE` deterministic tie-breaking) and `5` filter operators reusing `catalog.api.MetricEvaluationSupport` (`FR2`).
- [x] Implement deadlock-free transactional hourly `usage_rollups` upsert + sub-hour edge queries + ACID reconciliation & `FOR UPDATE SKIP LOCKED` crash recovery + `15s`-TTL after-commit Caffeine `liveEstimates` + deduplicated `50/80/100%` `UsageThresholdReachedEvent` foundation + low-cardinality Micrometer metrics + customer-isolated `MeteringController` + reproducible `perf/k6/` scripts (`MeteringUnitTests` `5/5`, `MeteringModuleIT` `6/6` PASS) (`FR7`, `QA1`–`QA5`).

### 4.5 `billing` — Two-Step Spring Batch Invoices, Tax, Credits, Credit Notes & Late Adjustments (`FR5`, `FR6`, `ADR-014`)
- [x] Add `V6__billing_schema.sql` (`billing_runs`, `tax_policies`, `invoices`, `invoice_lines` with full provenance + `seat_quantity`, `invoice_tax_lines`, `prepaid_credit_grants`, `credit_transactions`, `credit_notes`, `credit_note_lines`, `billing_adjustments`, sequential numbering sequences, partial unique `uq_invoices_sub_period_active`, and `3` PostgreSQL immutability triggers protecting finalized invoices & lines).
- [x] Implement `generateDraftInvoicesJob` and `finalizeInvoicesJob` (`chunk = 100`, keyset-paginated due draft reader) reusing `RatingEngineFacade` + authoritative `UsageQueryFacade` (never Caffeine `liveEstimates`), deterministic `TaxCalculationStrategy` SPI + `tax_policies`, FIFO `SELECT ... FOR UPDATE` credit drawdown, and transactional `InvoiceFinalizedEvent` outbox emission (`FR5`, `FR6`).
- [x] Implement immutable `credit_notes` (proportional tax reversal, cumulative limit caps, wallet refund on paid invoices, `CreditNoteIssuedEvent`), cumulative-delta `billing_adjustments` consuming `LateUsageQueryFacade`, low-cardinality `BillingMetrics`, customer-isolated `BillingController`, and full verification (`BillingUnitTests` `8/8`, `BillingModuleIT` `8/8`, `ModularityTests` `1/1`, `MeterFlowContextAndFlywayIT` `2/2` PASS) (`FR5`, `FR6`).
- [x] Extend read-only `FR10` simulation with historical invoice comparison (`HistoricalInvoiceSimulationFacade` / `HistoricalInvoiceSimulationService`, `@Transactional(readOnly = true)`, zero DB writes, exact `Money` deltas, customer `PUBLISHED`-only guard, verified in `WebhookNotificationAndSimulationIT` & `PortalUiIT`).

### 4.6 `payments` — Mock Collection, Exact Day 1/3/7 Retries & Separate Suspension Timer (`FR8`, `ADR-007`, `ADR-015`)
- [x] Add `V7__payments_and_dunning_schema.sql` (forward extension of `subscriptions` constraints for `SUSPENDED`, `payment_reference_seq`, `dunning_policies` seeded with `[24, 72, 168]` & `336h`, `dunning_cases` with `next_retry_at` and separate `suspension_at`, `payment_transactions` with `uq_payment_tx_idempotency` & `uq_captured_payment_per_invoice`, append-only `payment_attempts` & `dunning_events`, `SKIP LOCKED` indexes, and `3` PostgreSQL immutability triggers).
- [x] Implement deterministic `MockPaymentGateway`, `PaymentProcessingService`, exact `3`-attempt retry worker (`DunningWorkerService` with `FOR UPDATE SKIP LOCKED`: Day 1, Day 3, Day 7 -> `FINAL_WARNING`), separate suspension transition timer (`FINAL_WARNING -> SUSPENDED` with `0` 4th payment attempt), ACID billing/subscription settlement & recovery (`PAST_DUE`/`SUSPENDED -> ACTIVE` while protecting `CANCELLED`/`EXPIRED`/`PAUSED`), `5` Spring Modulith outbox events, low-cardinality `PaymentMetrics`, customer-isolated `PaymentController` (hiding simulation controls), and full verification (`PaymentAndDunningUnitTests` `6/6`, `PaymentModuleIT` `6/6`, `ModularityTests` `1/1`, `MeterFlowContextAndFlywayIT` `2/2` PASS) (`FR8`).

### 4.7 `webhooks`, `notifications` & `portal` (`FR1`–`FR10`, `QA4`, `C7`)
- [x] Add `V8__webhooks_and_notifications_schema.sql` (`webhook_endpoints` with AES-256-GCM encrypted `active_signing_secret_enc` & `previous_signing_secret_enc` + `previous_secret_expires_at`, `webhook_deliveries`, immutable `webhook_delivery_attempts` with `replayed_by_user_id` & `signature_timestamp`, `notifications`, and immutable `audit_logs`).
- [x] Implement `webhooks` supporting mandatory `invoice.finalised`, `payment.failed`, and `usage.threshold_reached` events (plus `payment.succeeded`, `credit_note.issued`, `subscription.suspended`, `subscription.reactivated`), dual-key `HMAC-SHA256` signing, constant-time `WebhookSignatureVerifier`, `SsrfUrlValidator`, `FOR UPDATE SKIP LOCKED` exponential backoff (`+30s`..`+6h`), persistent immutable attempt logs, and RBAC-scoped auditable manual replay with fresh timestamp signing (`FR9`, `WebhookSecurityAndBackoffUnitTests` `4/4` & `WebhookNotificationAndSimulationIT` `1/1` PASS).
- [x] Implement `notifications` alerts/audit logs and `portal` REST adapters + complete Thymeleaf Customer Portal (`/customer/**`, 11 views) & Admin/Operator Console (`/operator/**`, `/admin/**`, 9 views) with external `meterflow.css` under strict `CSP: default-src 'self'; frame-ancestors 'none'` and CSRF protection (`PortalUiIT` `1/1` PASS).

---

## 5. Automated Verification & Load Testing (`QA1`–`QA5`, `C9`, `C10`)

- [x] Unit tests (`63/63` PASS) covering all `5` aggregations, `5` filter operators, all `6` mandatory charge types (`FLAT_FEE`..`PER_SEAT`), OCP custom-strategy extension, free allowances, minimum commitments, `NUMERIC(19,8)` proration, penny rounding, and dual-key `HMAC-SHA256` signing / AES-256-GCM / SSRF / backoff (`C9`).
- [x] `ModularityTests` (`1/1` PASS) asserting zero cycles and zero internal package leaks across all `10` modules (`C1`, `QA3`).
- [x] Testcontainers PostgreSQL 17 integration tests (`37/37` PASS) covering `V1`–`V8`, `idempotency_key` `200`/`409` & concurrency, `72h` cutoffs, `1,000`-event batch, `< 60s` live freshness (`0.0000%` estimate deviation), two-step Spring Batch restart, cumulative-delta late adjustments, exact Day 1/3/7 retries + separate suspension timer, live JDK `HttpServer` webhook backoff/rotation/replay, `FR10` zero-write historical simulation, and `QaEngineeringValidationIT` (`C9`, `QA1`–`QA5`).
- [x] MockMvc security & UI suite (`SecurityFoundationTests`, `IdentityAndSecurityIT`, `WebhookNotificationAndSimulationIT`, `PortalUiIT`) verifying RBAC, CSRF, strict CSP, cross-customer IDOR rejection across invoices/simulations/notifications/portals/webhook replays, and `DRAFT` simulation guard (`QA4`).
- [ ] Optional host `k6` CLI load suite (`perf/k6/*.js`) executed on a `k6`-equipped host (`perf/k6/*.js` authored; Java Testcontainers `QaEngineeringValidationIT` benchmark executed and logged in `docs/WORK_LOG.md`) (`C10`, `QA1`).
