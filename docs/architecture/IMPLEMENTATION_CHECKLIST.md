# MeterFlow Implementation Checklist (`docs/architecture/IMPLEMENTATION_CHECKLIST.md`)

## 1. Checklist Rules

This checklist tracks concrete deliverables across **architecture documentation, build/CI infrastructure, the ten Spring Modulith modules, and automated verification suites** (`FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`).

- Check `[x]` **only** when artifacts exist on disk and pass verification.
- Currently, **100% of Documentation Foundation (`§2`) and Build/PostgreSQL/Flyway/CI Baseline (`§3`) items are `[x]`**, while **100% of `FR1`–`FR10` domain module implementations (`§4`–`§5`) remain `[ ]`** (`Not Started`).

---

## 2. Documentation & Architecture Foundation (`C1`–`C4`, `C12`)

- [x] Establish portable [`AGENT.md`](../../AGENT.md), [`MEMORY.md`](../../MEMORY.md), [`PLAN.md`](../../PLAN.md), [`CHANGELOG.md`](../../CHANGELOG.md), [`docs/WORK_LOG.md`](../WORK_LOG.md), and [`docs/AI_USAGE_LOG.md`](../AI_USAGE_LOG.md).
- [x] Align [`docs/PROJECT_CHARTER.md`](../PROJECT_CHARTER.md) and [`docs/REQUIREMENTS_TRACEABILITY.md`](../REQUIREMENTS_TRACEABILITY.md) across `FR1`–`FR10`, `QA1`–`QA5`, and `C1`–`C12`.
- [x] Align [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md), [`MODULE_BOUNDARIES.md`](./MODULE_BOUNDARIES.md), [`DATA_MODEL.md`](./DATA_MODEL.md), [`QUALITY_ATTRIBUTE_PLAN.md`](./QUALITY_ATTRIBUTE_PLAN.md), [`UTILITY_TREE.md`](./UTILITY_TREE.md), [`PATTERN_JUSTIFICATION.md`](./PATTERN_JUSTIFICATION.md), and this checklist.
- [x] Establish [`ADRs/README.md`](./ADRs/README.md) and nine accepted ADRs (`ADR-001` through `ADR-009`).

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

### 4.1 `shared` & `identity` (`C1`, `C6`, `QA2`, `QA4`)
- [ ] Implement `shared.Money` (`NUMERIC(19,8)` intermediate / `NUMERIC(19,4)` `HALF_UP` line rounding), `BillingPeriod`, `ProrationCalculator`, and injectable `Clock`.
- [ ] Add `V2__identity_schema.sql` (`users`, `user_roles`) and dual Spring Security 7 chains (`BCrypt`, CSRF on portal, stateless `/api/v1/events/**`, principal-derived `customerId` scoping).

### 4.2 `catalog` — Metrics, Strategy/Composite Six Mandatory Charge Types & Immutable Plan Versions (`FR2`, `FR3`, `FR10`)
- [ ] Add `V3__catalog_metrics_and_plans_schema.sql` (`billable_metrics`, `metric_filters`, `pricing_plans`, `plan_versions`, `plan_charges` with `FLAT_FEE`/`PER_UNIT`/`GRADUATED_TIERS`/`VOLUME_TIERS`/`PACKAGE`/`PER_SEAT` + `charge_config JSONB`, `charge_tiers`, DB immutability triggers on `PUBLISHED` `plan_versions`).
- [ ] Implement `BillableMetric` definitions with configurable `JSONB` property filters (`EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `EXISTS`) (`FR2`).
- [ ] Implement immutable `PlanVersion` publishing (`DRAFT` -> `PUBLISHED` -> `ARCHIVED`) cached in Caffeine `planVersions` (`60m`) (`FR10`).
- [ ] Implement `ChargeCalculationStrategy` + `ChargeStrategyRegistry` + `PlanRatingComposite` (`RatingEngineFacade`) with all `6` mandatory strategies (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`), free allowances, and charge/plan minimum commitments (`FR3`, `QA3`).

### 4.3 `subscriptions` — Trials, Seat Quantities, Mid-Cycle Switches & Proration (`FR3`, `FR4`)
- [ ] Add `V4__customers_and_subscriptions_schema.sql` (`customer_accounts`, `subscriptions` with `seat_quantity`, `subscription_segments` with `seat_quantity` & `proration_factor NUMERIC(19,8)`).
- [ ] Implement subscription transitions (`PENDING`, `TRIAL`, `ACTIVE`, `PAST_DUE`, `SUSPENDED`, `PAUSED`, `CANCELED`, `EXPIRED`), trial auto-conversion, and mid-cycle plan or seat-quantity proration segments (`FR4`).

### 4.4 `metering` — Idempotency Key, 72h Gate, Five Aggregations, Proven `<= 1m` Live Estimates & Budgets (`FR1`, `FR2`, `FR7`)
- [ ] Add `V5__metering_rollups_and_budgets_schema.sql` (`usage_events` with `uq_usage_events_customer_idempotency (customer_id, idempotency_key)`, `payload_hash CHAR(64)`, GIN `properties` index; `rejected_usage_events`; `usage_rollups` keyed by `bucket_hour`; `spend_budgets`; `budget_alerts_fired`).
- [ ] Implement single & `1,000`-event batch ingestion executing idempotency check **before** `72h` expiration check (`200` duplicate on matching `payload_hash`, `409` conflict on mismatched `payload_hash`, `422` on `> 72h` or `> +5m` future, `PriorPeriodLateEventRecorded` for `<= 72h` closed periods) (`FR1`).
- [ ] Implement all `5` aggregation types (`COUNT`, `SUM`, `MAX`, `UNIQUE_COUNT` exact distinct, `LATEST`) with `JSONB` filter evaluation (`FR2`).
- [ ] Implement synchronous `bucket_hour` `usage_rollups` upsert + `15s`-TTL after-commit Caffeine `liveEstimates` (`<= 15.65s` worst-case freshness `<< 60s`), plus `10s` budget evaluator firing idempotent `usage.threshold_reached` alerts (`FR7`, `QA1`).

### 4.5 `billing` — Two-Step Spring Batch Invoices, Tax, Credits, Credit Notes, Late Adjustments & Simulation (`FR5`, `FR6`, `FR10`)
- [ ] Add `V6__billing_credits_and_adjustments_schema.sql` (`billing_runs`, `invoices`, `invoice_lines` with full provenance + `seat_quantity`, `invoice_tax_lines`, `credit_grants`, `credit_ledger_entries`, `credit_notes`, `credit_note_lines`, `late_usage_adjustments`, DB immutability trigger on `FINALIZED` invoices).
- [ ] Implement `generateDraftInvoicesJob` and `finalizeInvoicesJob` (`chunk = 100`) never reading Caffeine `liveEstimates`, applying tax and FIFO credit drawdown, and emitting `InvoiceFinalizedEvent` (`invoice.finalised`) (`FR5`, `FR6`).
- [ ] Implement immutable `credit_notes` (proportional tax reversal) and cumulative-delta `late_usage_adjustments` (`FR6`).
- [ ] Implement `@Transactional(readOnly = true)` `InvoiceSimulationFacade` reusing `RatingEngineFacade` with RBAC (`DRAFT` restricted to Admin/Operator) and `0` DB writes (`FR10`).

### 4.6 `payments` — Simulated Collection, Exact Day 1/3/7 Retries & Separate Suspension Timer (`FR8`)
- [ ] Add `V7__payments_ledger_and_dunning_schema.sql` (`payment_transactions` with `uq_payment_idempotency` & `uq_captured_payment_per_invoice`, `ledger_entries`, `dunning_policies` with `retry_offsets_hours = [24, 72, 168]` and separate `suspension_offset_hours`, `dunning_cases` with `next_retry_at` and separate `suspension_at`, `dunning_attempts`).
- [ ] Implement `SimulatedPaymentService`, exact `3`-attempt retry worker (Day 1, Day 3, Day 7 -> `FINAL_WARNING`), separate suspension transition timer (`FINAL_WARNING -> SUSPENDED` with `0` 4th payment attempt), customer notifications at every transition (`payment.failed` on failures), and recovery to `RECOVERED`/`ACTIVE` on settlement at any applicable point (`FR8`).

### 4.7 `webhooks`, `notifications` & `portal` (`FR1`–`FR10`, `QA4`, `C7`)
- [ ] Add `V8__webhooks_notifications_and_audit_schema.sql` (`webhook_endpoints` with encrypted `active_signing_secret_enc` & `previous_signing_secret_enc` + `previous_secret_expires_at`, `webhook_deliveries`, `webhook_delivery_attempts` with `replayed_by_user_id` & `signature_timestamp`, `notification_logs`, `audit_logs`).
- [ ] Implement `webhooks` supporting mandatory `invoice.finalised`, `payment.failed`, and `usage.threshold_reached` events, dual-key `HMAC-SHA256` signing, constant-time verification helper, SSRF IP guard, `FOR UPDATE SKIP LOCKED` exponential backoff (`30s`..`6h`), persistent attempt logs, and RBAC-scoped manual replay with fresh timestamp signing (`FR9`).
- [ ] Implement `notifications` alerts/audit logs and `portal` `/api/v1/events` + Thymeleaf consoles across `FR1`–`FR10`.

---

## 5. Automated Verification & Load Testing (`QA1`–`QA5`, `C9`, `C10`)

- [ ] Unit tests covering all `5` aggregations, `5` filter operators, all `6` mandatory charge types (`FLAT_FEE`..`PER_SEAT`), OCP custom-strategy extension, free allowances, minimum commitments, `NUMERIC(19,8)` proration, penny rounding, and dual-key `HMAC-SHA256` signing (`C9`).
- [ ] `ModularityTests` asserting zero cycles and zero internal package leaks (`C1`, `QA3`).
- [ ] Testcontainers PostgreSQL integration tests covering `idempotency_key` `200`/`409` & `50`-VU concurrency, `72h` cutoffs, `1,000`-event batch, `< 60s` live freshness, two-step Spring Batch restart, cumulative-delta late adjustments, exact Day 1/3/7 retries + separate suspension timer, and webhook backoff/rotation/replay (`C9`, `QA2`, `QA5`).
- [ ] MockMvc security suite verifying RBAC, CSRF, cross-customer IDOR rejection across invoices/simulations/webhook replays, and `DRAFT` simulation guard (`QA4`).
- [ ] `k6` load suite (`perf/k6/*.js`) executed against Local Docker Compose PostgreSQL with factual results logged in `docs/WORK_LOG.md` (`C10`, `QA1`).
