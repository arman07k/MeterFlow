# MeterFlow — University Project Defence Guide (15-Minute Presentation)

This guide maps every MeterFlow architectural claim directly to implemented code, PostgreSQL constraints, and verified test evidence so you can defend the system cleanly and factually in a 15-minute review.

---

## Suggested 15-Minute Agenda

1. **Problem & Architectural Scope (2 min)**: Usage-based billing pitfalls (duplicate events, floating-point drift, mid-cycle proration, double billing, webhook spoofing) and why we chose a **Spring Modulith modular monolith** over microservices (`ADR-001`).
2. **System Context, Containers & Module Boundaries (2 min)**: 10 modules, strict `::api` contracts, acyclic DAG verified automatically by `ModularityTests`.
3. **Database & Financial Integrity (3 min)**: `Money` (`BigDecimal`, `HALF_UP`, BDT/USD isolation), Flyway `V1`–`V8`, PostgreSQL immutability triggers on finalized invoices, credit notes, payment attempts, webhook attempts, and audit logs.
4. **Core Flows & Concurrency (4 min)**:
   - Batch ingestion (`ON CONFLICT DO NOTHING`, 72h late window, rollups)
   - Strategy-pattern pricing engine (6 built-in + 7th OCP custom strategy)
   - Chunked Spring Batch 6 billing runs with checkpoint resume
   - Payments & Dunning (`FOR UPDATE SKIP LOCKED` worker, Day 1/3/7 retries, separate suspension step)
   - HMAC-SHA256 webhooks (AES-256-GCM at rest, 24h dual-key rotation, exponential backoff, auditable replay)
   - Read-only historical invoice simulation (`FR10`)
5. **Security, UI Demo & Live Evidence (2 min)**: Dual SecurityFilterChains, CSRF, strict CSP, Customer Portal vs. Operator Console, `100/100` passing tests & QA1–QA5 metrics.
6. **Q&A Preparation (2 min)**.

---

## Architectural Highlights & Where to Show Them in Code

### 1. Spring Modulith Boundaries (Zero Accidental Coupling)
- **What to say**: MeterFlow is organized into 10 bounded modules (`shared`, `identity`, `catalog`, `subscriptions`, `metering`, `billing`, `payments`, `webhooks`, `notifications`, `portal`). Cross-module calls are restricted to `@NamedInterface("api")` packages and Spring Modulith transactional outbox events.
- **Proof**: `src/test/java/com/meterflow/ModularityTests.java` fails the Maven build immediately if any module introduces a circular dependency or accesses another module's `internal` package.

### 2. Financial Precision & Single-Currency Discipline (`ADR-010`)
- **What to say**: Zero `double` or `float` types exist in financial logic. Every monetary calculation runs through `com.meterflow.shared.Money` with explicit rounding scales: intermediate `scale = 8`, persisted `scale = 4`, payable minor units `scale = 2` (paisa/cent), `RoundingMode.HALF_UP`. Cross-currency operations fail fast with `CurrencyMismatchException`.
- **Proof**: `MoneyUnitTests` + database `CHECK` constraints across `V3`–`V7`.

### 3. Idempotency & Concurrency Without External Brokers
- **What to say**: Instead of adding Redis or Kafka, PostgreSQL enforces durability and concurrency cleanly:
  - **Metering**: `UNIQUE (customer_id, idempotency_key)` + payload SHA-256 hash conflict detection; PostgreSQL `ON CONFLICT` upserts for hourly rollups (`V5`).
  - **Subscriptions & Invoicing**: PostgreSQL transaction-scoped advisory locks (`pg_advisory_xact_lock`) + partial unique indexes prevent duplicate open subscriptions or double billing (`V4`, `V6`).
  - **Dunning & Webhooks**: `SELECT ... FOR UPDATE SKIP LOCKED` workers (`DunningCaseRepository`, `WebhookDeliveryRepository`) allow concurrent workers with zero duplicate retries or duplicate webhook dispatches (`V7`, `V8`).
  - **Outbox**: Spring Modulith `event_publication` table guarantees domain events commit atomically with database writes (`V1`).

### 4. Pricing Engine Extensibility (Open/Closed Principle — `QA3`)
- **What to say**: All 6 mandatory charge models (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`) implement `ChargeCalculationStrategy` and self-register via Spring DI into `ChargeStrategyRegistry`. Charge types are strings, not a closed enum.
- **Proof**: `PricingEngineUnitTests` registers a 7th custom strategy bean at runtime and rates a plan with zero modifications to existing rating code.

### 5. Database-Enforced Financial Immutability
- **What to say**: Application guards alone are not enough for financial records. PostgreSQL `BEFORE UPDATE OR DELETE` triggers strictly block tampering on `invoices` (once `FINALIZED`/`PAID`/`VOID`), `invoice_lines`, `credit_notes`, `payment_transactions`, `payment_attempts`, `webhook_delivery_attempts`, and `audit_logs`.
- **Proof**: `BillingModuleIT` and `PaymentModuleIT` intentionally execute raw SQL `UPDATE`/`DELETE` statements via `JdbcTemplate` and assert PostgreSQL rejects them with `DataAccessException`.

### 6. Secure Outbound Webhooks (`FR9`, `ADR-009`)
- **What to say**: Signing secrets (`whsec_...`) are generated via `SecureRandom`, encrypted at rest with `AES-256-GCM` (fresh 12-byte IV per secret), and disclosed strictly once on creation or rotation. Secret rotation enables a 24-hour dual-key grace window (`X-MeterFlow-Signature: t=<epoch>,v1=<active>,v1=<previous>`). Outbound targets pass SSRF validation blocking loopback, RFC-1918, and cloud metadata IPs. Manual replay signs with a fresh timestamp, appends an immutable attempt row, writes an `audit_logs` entry, and never mutates financial data.
- **Proof**: `WebhookSecurityAndBackoffUnitTests` + `WebhookNotificationAndSimulationIT` (live local JDK `HttpServer` verifying `503 -> 503 -> 200` backoff, dual-key HMAC verification, replay audit, and cross-tenant `403`).

### 7. Read-Only Historical Invoice Simulation (`FR10`)
- **What to say**: `HistoricalInvoiceSimulationService` is annotated `@Transactional(readOnly = true)` and reuses `UsageQueryFacade`, `RatingEngineFacade`, and `TaxCalculationStrategy` over a finalized invoice's exact historical window. Customers can compare against `PUBLISHED` plan versions only; staff can also simulate against `DRAFT` versions.
- **Proof**: `WebhookNotificationAndSimulationIT` asserts exact `Money` deltas (`-1,150.00 BDT` / `-33.33%`), verifies zero DB row count changes across invoices/credits/payments, and proves customers receive `403 Forbidden` on `DRAFT` plans or other tenants' invoices.

### 8. Security & Server-Rendered Portals (`ADR-006`)
- **What to say**: Dual `SecurityFilterChain`s isolate stateless `/api/v1/**` (HTTP Basic + RFC 9457 `ProblemDetail`) from stateful Thymeleaf portals (`/customer/**`, `/operator/**`, `/admin/**`) with BCrypt, CSRF tokens on every POST form, session fixation migration, `CustomerAccessGuard` tenant isolation, and strict `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'` with zero inline styles or scripts.
- **Proof**: `SecurityFoundationTests`, `IdentityAndSecurityIT`, and `PortalUiIT`.

---

## Verified Quantitative Evidence (Canonical `QA1`–`QA5`)

All numbers below are genuine, reproducible measurements from `./mvnw -B clean verify` and `QaScaleBenchmarkIT` (PostgreSQL 17 Testcontainers on local Docker; see [`TESTING_AND_BENCHMARK_REPORT.md`](./TESTING_AND_BENCHMARK_REPORT.md) for methodology):

| Quality Attribute | Metric / Scenario | Measured Result |
|---|---|---|
| **Verification Suite (`C9`)** | Automated Tests | **100 / 100 core PASS** (63 Surefire + 37 Failsafe) + **2 / 2 opt-in scale benchmarks PASS** (`0` failures/errors) |
| **Toolchain Compatibility** | Compilation | Java 25 (`release 25`) & Java 21 (`-Pjava21`, `release 21`) — `432` main + `23` test files, `0` warnings |
| **QA1 (Performance, Throughput & Freshness)** | 1M events (4 threads) + batch-250 / batch-1,000 + 10k-customer Spring Batch + live estimate freshness | **`16,586.5 events/sec`** (`60.29 s`); batch-250 **`p50 = 35 ms`, `p95 = 46 ms`, `p99 = 49 ms`**; batch-1,000 **`p50 = 204 ms`, `p95 = 295 ms`, `p99 = 371 ms`** *(HTTP Basic BCrypt adds ~100 ms per HTTP call)*; `10,000` customers billed in **`5m 42.2s`** (`181.6 s` draft + `160.6 s` finalization, `163 MiB` heap); `15s` Caffeine TTL freshness |
| **QA2 (Financial Correctness & Reliability)** | 1M unique + 30k dup (3%) + 20k late (2%) + 10k re-run + estimate parity | `0` errors, `143 MiB` heap, **0.00 paisa** deviation across all 10 customers (`6,050.00 BDT` exact/customer); **`0.0000%`** live-estimate vs. finalized deviation; `197 ms` 10k-customer re-run (`0` duplicates); `13` DB immutability triggers verified |
| **QA3 (Modularity & OCP Extensibility)** | 10 Spring Modulith modules + 7th custom `ChargeCalculationStrategy` | `ModularityTests` (`1/1` PASS, `0` cycles/leaks); 7th strategy verified with `0` edits to existing pricing code |
| **QA4 (Security & Webhook Integrity)** | RBAC, CSRF, strict CSP, tenant isolation, AES-256-GCM, dual-key HMAC, SSRF | 100% pass across `SecurityFoundationTests`, `IdentityAndSecurityIT`, `WebhookSecurityAndBackoffUnitTests`, `WebhookNotificationAndSimulationIT`, and `PortalUiIT` |
| **QA5 (Testability, Reproducibility & Observability)** | Offline Testcontainers CI, `Clock` injection, JSONB line provenance, Docker Compose health | `100/100` core + `2/2` scale PASS offline; `/actuator/health/readiness` `HTTP 200 UP`; JSONB provenance on 100% of invoice lines; zero-write `FR10` simulation |

## Honest Limitations to State Transparently

1. **HTTP Basic BCrypt Latency**: Over stateless HTTP Basic (`BCrypt` strength 12), per-request password hashing adds ~100 ms of CPU overhead; direct service/DB batch-250 latency is `p95 = 46 ms` (`<= 100 ms`), and production API deployments would pair MeterFlow with cached API tokens/JWTs for sub-100 ms HTTP `p95`.
2. **External Integrations Intentionally Simulated**: Payment collection uses a deterministic internal `MockPaymentGateway`, notifications persist in PostgreSQL without paid email/SMS gateways, and `supabase` profile connectivity requires external credentials not bundled in the repository.
3. **Host `k6` CLI**: Reproducible `perf/k6/*.js` load scripts are versioned in the repository; full-scale local verification was executed directly via `QaScaleBenchmarkIT` on PostgreSQL 17 Testcontainers because `k6` is not installed on the local workstation.

---

## Likely Defence Questions & Crisp Answers

1. **Why a modular monolith instead of microservices?**
   Billing requires strong ACID consistency across usage rollups, credits, invoices, and outbox events. Spring Modulith gives strict compile/test-verified module boundaries without distributed transaction overhead, while keeping extraction seams clean via `::api` facades and domain events.
2. **How do you prevent double billing if a batch job crashes mid-run?**
   Spring Batch 6 persists chunk checkpoints (`batch_step_execution`), `invoices` enforces a partial unique index per active `(subscription_id, period_start, period_end)`, and `InvoiceManagementService` takes a per-subscription PostgreSQL advisory lock (`pg_advisory_xact_lock`). Resuming or re-running skips already-finalized invoices cleanly.
3. **Why not use Redis for rate/estimate caching or Kafka for webhooks?**
   Per `ADR-002`, `ADR-008`, and `ADR-009`, Caffeine provides sub-millisecond in-process caching for non-authoritative live estimates (authoritative billing always reads PostgreSQL), while PostgreSQL `event_publication` + `FOR UPDATE SKIP LOCKED` provides transactional delivery durability with zero extra broker infrastructure.
4. **How are late-arriving events handled after an invoice is finalized?**
   Events arriving after the 72-hour grace window or for a finalized period never mutate the finalized invoice. They are persisted into `usage_late_event_records` and converted into explicit `LATE_USAGE` billing adjustments attached to the customer's next billing period (`FR1`, `FR6`).
5. **Can a customer trigger mock payments or view draft pricing plans?**
   No. Mock payment controls exist strictly on `/api/v1/payments/**` and `/operator/payments` (`ADMIN`/`OPERATOR` only). Customer Portal `/customer/payments` is read-only, and `HistoricalInvoiceSimulationService` rejects `CUSTOMER` requests targeting `DRAFT` plan versions with `403 Forbidden`.
