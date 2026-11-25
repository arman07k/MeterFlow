# MeterFlow — Testing, QA & Performance Benchmark Report (`docs/TESTING_AND_BENCHMARK_REPORT.md`)

## 1. Purpose & Evidence Policy

This report summarizes the actual, reproducible automated tests, full-scale benchmarks, toolchain compatibility checks, and containerized runtime checks executed against the MeterFlow repository. Every metric reported below comes from direct local execution against **PostgreSQL 17 (`postgres:17-alpine`)** via Testcontainers or Docker Compose—zero metrics are estimated or fabricated.

---

## 2. Automated Test Suite Inventory (`23` Test Classes)

### 2.1 Surefire Unit, Architecture & Security Suite (`12` Classes, `63` Tests)

| Test Class | Tests | Scope Verified |
| :--- | ---: | :--- |
| `ModularityTests` | 1 | `ApplicationModules.of(MeterFlowApplication.class).verify()` across all `10` Spring Modulith modules (`0` cycles, `0` illegal internal imports) |
| `MoneyUnitTests` | 6 | Canonical immutable `Money` (`BDT`/`USD`), exact intermediate `BigDecimal`, `HALF_UP` rounding (`scale=8/4/2`), currency mismatch rejection, plain-decimal JSON |
| `SharedFoundationUnitTests` | 7 | `BillingInterval`, `BillingPeriod`, `CorrelationIds`, `SchedulingConfiguration` toggle, foundation invariants |
| `IdentityUnitTests` | 3 | Email normalization, password validation, RBAC role/membership rules |
| `SecurityFoundationTests` | 8 | Dual `SecurityFilterChain`s, stateless `/api/v1/**` RFC 9457 `401`/`403`, stateful CSRF enforcement, strict CSP header, session & logout guards |
| `MetricDefinitionUnitTests` | 4 | All `5` aggregation types (`SUM`, `COUNT`, `MAXIMUM`, `UNIQUE_COUNT`, `LATEST_VALUE`), `5` filter operators, deterministic tie-breaking, invalid property rejection |
| `PricingEngineUnitTests` | 6 | All `6` mandatory `ChargeCalculationStrategy` beans (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE` ceiling, `PER_SEAT`), single-pass allowances, non-duplicative commitments, and runtime **7th custom OCP strategy** without modifying existing pricing code |
| `BillingPeriodAndProrationUnitTests` | 5 | Calendar-clamped zoned billing periods and exact-seconds UTC `EXACT_SECONDS_V1` proration (`NUMERIC(19,8)` factor -> `NUMERIC(19,4)` amounts) |
| `MeteringUnitTests` | 5 | Batch size limits (`<= 1,000`), canonical `SHA-256` payload hashing, `72h` late-window and `+5m` future-timestamp rules |
| `BillingUnitTests` | 8 | Tax calculation strategy, credit FIFO ordering, credit note proportional tax reversal & caps, invoice state transitions |
| `PaymentAndDunningUnitTests` | 6 | Deterministic `MockPaymentGateway` modes, exact Day 1/3/7 (`[24, 72, 168]`) retry schedule, separate suspension timer (`0` 4th retry) |
| `WebhookSecurityAndBackoffUnitTests` | 4 | AES-256-GCM secret encryption, dual-key `HMAC-SHA256` constant-time verification & `300s` replay window, `SsrfUrlValidator`, exponential backoff (`+30s/+2m/+10m/+1h/+6h`) |

### 2.2 Failsafe Integration Suite (`10` Core Classes = `37` Tests + `1` Opt-In Scale Class = `2` Tests)

All integration tests execute against an ephemeral **PostgreSQL 17 (`postgres:17-alpine`)** Testcontainers instance with Flyway `V1`–`V8` applied and Hibernate `ddl-auto: validate` enabled:

| Integration Test Class | Tests | Scope Verified |
| :--- | ---: | :--- |
| `MeterFlowContextAndFlywayIT` | 2 | Full Spring Boot context startup, Flyway `V1`–`V8` validation, Hibernate `ddl-auto: validate`, Spring Batch 6 repository, Spring Modulith outbox & `OutboxRetryScheduler`, OpenAPI |
| `IdentityAndSecurityIT` | 4 | Persistent `customers`/`users`/`customer_memberships`, `DatabaseUserDetailsService` (`BCrypt(12)`), `CustomerAccessGuard`, cross-tenant IDOR `403`, one-time admin bootstrap |
| `CatalogModuleIT` | 3 | Metric CRUD & filters, immutable `PUBLISHED` plan versions (`5` PostgreSQL triggers verified via raw SQL), version cloning, Caffeine caching, read-only pricing simulation |
| `SubscriptionModuleIT` | 5 | Single-open-subscription constraint & advisory locking, trials (`EXPIRE_ON_END` / `AUTO_CONVERT_TO_PAID`), immediate mid-cycle proration, scheduled changes, pause/resume shift, cancellation, REST RBAC |
| `MeteringModuleIT` | 6 | `UNNEST` batch ingestion (partial acceptance), `200` duplicate vs. `409` payload-hash conflict, `72h` late events, all `5` aggregations & `5` filters, hourly rollups & reconciliation/recovery, `15s` Caffeine live estimates, `50%`/`80%`/`100%` threshold alerts |
| `BillingModuleIT` | 8 | Two-step `DRAFT` -> immutable `FINALIZED` invoices, raw SQL immutability trigger rejection (`3` triggers), 6-thread concurrent draft generation, 4-thread FIFO credit drawdown, credit notes & wallet refund, late-usage cumulative-delta & manual adjustments, Spring Batch 6 reruns & crash/resume recovery |
| `PaymentModuleIT` | 6 | Single-capture uniqueness under concurrency, raw SQL immutability triggers (`3` triggers), exact Day 1/3/7 retries -> `FINAL_WARNING` -> separate `SUSPENDED` timer (`0` 4th retry), recovery reactivation protecting `CANCELLED`/`EXPIRED`/`PAUSED`, customer isolation hiding mock controls |
| `WebhookNotificationAndSimulationIT` | 1 | Live local JDK `HttpServer` verifying `503 -> 503 -> 200` backoff, dual-key HMAC rotation, auditable manual replay into immutable `audit_logs`, customer notifications, and `FR10` read-only historical invoice simulation (`-1,150.00 BDT` / `-33.33%`, `0` DB writes, `403` on customer `DRAFT` attempt) |
| `PortalUiIT` | 1 | End-to-end MockMvc verification of Customer Portal (`/customer/**`) and Operator Console (`/operator/**`, `/admin/**`), CSRF enforcement, strict CSP header, cross-tenant `403`, and read-only customer payment view |
| `QaEngineeringValidationIT` | 1 | Default-run QA validation (`5,000` unique + `150` dup + `100` late events, `50`-customer Spring Batch run, `0.00` paisa drift, `0.0000%` estimate deviation) |
| `QaScaleBenchmarkIT` *(opt-in)* | 2 | Gated by `-Dmeterflow.scale.benchmark=true`: full `1,000,000`-event ingestion & reconciliation + `10,000`-customer Spring Batch 6 billing run |

---

## 3. Verified Execution Commands & Outcomes

To distinguish directly executed checks from previously verified runs on the unchanged codebase (`4904d03` — zero Java, test, SQL migration, or runtime config edits have occurred since):

### 3.1 Executed During the Final Submission & Documentation Audit (`2026-11-25`)

| Check | Command | Verified Result |
| :--- | :--- | :--- |
| Maven build, `ModularityTests`, unit & security suite (Surefire) | `./mvnw -B test` | **PASS** (`51.961 s`) — **`63 / 63` tests PASS** (`0` failures, `0` errors, `0` skipped) |
| Docker Compose configuration validation | `docker compose config -q && docker compose --profile full config -q` | **PASS** (`exit 0` for both base and `full` profile) |
| Static documentation, link, secret & immutability audit | `git diff --check`, link/secret/terminology/migration-diff checks | **PASS** (`0` broken relative links, `0` real secrets, `0` machine paths, `0` prohibited tracking terms, `0` code/migration diffs) |

### 3.2 Previously Verified Baseline Against Unchanged Codebase (`4904d03` — Not Redundantly Rerun)

| Check | Command | Reported Verified Result (from `docs/WORK_LOG.md`) |
| :--- | :--- | :--- |
| Full build, Modulith, unit & PostgreSQL 17 Testcontainers ITs | `./mvnw -B clean verify` | **PASS** — `100 / 100` core tests (`63` Surefire + `37` Failsafe, `0` failures/errors; `2` opt-in scale tests skipped by default), `432` main + `23` test sources, `0` compiler warnings |
| Java 21 LTS compatibility check | `./mvnw -B -Pjava21 clean test-compile` | **PASS** — `432` main + `23` test sources compiled cleanly with `--release 21`, `0` warnings |
| Opt-in full-scale PostgreSQL 17 benchmarks | `./mvnw -B test-compile failsafe:integration-test failsafe:verify -Dit.test=QaScaleBenchmarkIT -Dmeterflow.scale.benchmark=true` | **PASS** (`2 / 2`, `07:56 min` on local Docker PostgreSQL 17 Testcontainers) |
| Full Docker Compose runtime smoke test | `docker compose --profile full up --build -d` | **PASS** — `meterflow-postgres` healthy (`51.71 MiB / 512 MiB`), `meterflow-app` healthy (`387.8 MiB / 512 MiB`), `/actuator/health/readiness` `HTTP 200` (`{"status":"UP"}`), `/` `HTTP 200` with strict CSP, `/login` `HTTP 200`, `/css/meterflow.css` `HTTP 200` |

### 3.3 Not Executed Locally

| Check | Status & Reason |
| :--- | :--- |
| External `k6` CLI load suite (`perf/k6/*.js`) | Authored and versioned; not executed locally because `k6` CLI is not installed on the audit workstation |
| Live Supabase PostgreSQL (`supabase` profile) | Configured in `application-supabase.yml` and statically verified; intentionally not live-connected without external credentials |
| Remote GitHub Actions CI run inspection | `.github/workflows/ci.yml` pushed to `origin/main` at `4904d03`; remote web workflow status was not inspected from this offline audit |

---

## 4. Performance & Quality Benchmark Evidence (`QA1`, `QA2`, `QA5`)

### 4.1 Test Environment & Methodology

- **Host environment**: Single Windows workstation running **Eclipse Temurin JDK `25.0.5.1+2-LTS`**, **Docker Desktop `29.9.3`**, and **PostgreSQL `17.12` (`postgres:17-alpine`)** inside Testcontainers on local loopback storage.
- **Methodology**:
  - `QaScaleBenchmarkIT` drives the real Spring application context (`UsageIngestionFacade`, `UsageReconciliationFacade`, `InvoiceManagementFacade`, `BillingRunFacade`) against real PostgreSQL 17 with Flyway `V1`–`V8` active and zero mocks.
  - Heap measurements reflect JVM used heap (`totalMemory() - freeMemory()`) sampled after workload completion inside the test JVM.
  - Because `QaScaleBenchmarkIT` invokes the Spring service layer directly (isolating ingestion + PostgreSQL + rollup + batch performance), earlier HTTP Basic `BCrypt(12)` benchmark measurements from `MeteringModuleIT` are reported alongside it so HTTP authentication overhead is never hidden.

### 4.2 Measured Results Summary

| Benchmark Workload | Canonical QA Mapping | Measured Metric | Result | Target / Notes |
| :--- | :--- | :--- | :--- | :--- |
| **1,000,000-Event Ingestion & Reconciliation** (`QaScaleBenchmarkIT`) | `QA1`, `QA2` | Volume & composition | `1,000,000` unique + `30,000` duplicates (`3%`) + `20,000` late (`2%`) across `10` customers | `0` errors |
| | `QA1` | Sustained multi-threaded throughput (4 threads) | **`16,586.5 events/sec`** (`60.29 s`) | Service + PostgreSQL `UNNEST` batching |
| | `QA1` | Batch-250 latency | **`p50 = 35 ms`, `p95 = 46 ms`, `p99 = 49 ms`** | Meets `p95 <= 100 ms` |
| | `QA1` | Max batch-1,000 latency (4-thread contention) | **`p50 = 204 ms`, `p95 = 295 ms`, `p99 = 371 ms`** | Larger batch amortizes per-event cost at higher per-request latency |
| | `QA2` | Financial reconciliation parity (`raw == rollups == finalized invoices`) | **`0.00` paisa deviation** (`6,050.00 BDT` exact per customer) | Exact `BigDecimal` parity |
| | `QA1` | JVM heap used | **`143 MiB`** | Well below `512 MiB` cap |
| **HTTP Basic `BCrypt(12)` Ingestion Benchmark** (`MeteringModuleIT` 20k-event run) | `QA1`, `QA4` | Full HTTP + `BCrypt(12)` + PostgreSQL (200-event batches, 4 threads) | **`4,092.5 events/sec`**, `httpP50 = 175.58 ms`, **`httpP95 = 267.30 ms`** | Stateless per-request `BCrypt(12)` adds `~109 ms` CPU cost; exceeds `100 ms` HTTP `p95` |
| **10,000-Customer Spring Batch 6 Billing** (`QaScaleBenchmarkIT`) | `QA1`, `QA2` | End-to-end duration (`chunk = 100`, keyset pagination) | **`5m 42.2s` (`342.2 s`)** — `181.6 s` draft + `160.6 s` finalization | Well under `10m` (`600 s`) target |
| | `QA1` | JVM heap used | **`163 MiB`** | Bounded chunk memory |
| | `QA2` | Idempotent re-run | **`197 ms`**, **`0` duplicate invoices** (`10,000` finalized intact) | Exact checkpoint/uniqueness safety |
| **Default QA Validation** (`QaEngineeringValidationIT`) | `QA1`, `QA2`, `QA5` | Live spend estimate vs. finalized invoice deviation | **`0.0000%`** deviation (`5,000` unique + `150` dup + `100` late; `50` customers billed in `2,373 ms` draft / `1,673 ms` finalization) | Meets `< 1%` accuracy target |

### 4.3 Benchmark Limitations vs. Production Guarantees

> **Important Distinction:** These measurements validate algorithmic efficiency, batch scaling, and database correctness on a single local developer workstation running Docker PostgreSQL 17. They are **not** production SLA guarantees. Production latency and throughput depend on network RTT, disk IOPS, shared database contention, TLS termination, concurrent multi-tenant query patterns, and replacing stateless per-request HTTP Basic `BCrypt(12)` with cached API keys or signed bearer tokens. Additionally, the `perf/k6/*.js` load scripts are authored and ready in [`perf/k6/`](../perf/k6/README.md) but were not executed locally because the `k6` CLI is not installed on the audit workstation.
