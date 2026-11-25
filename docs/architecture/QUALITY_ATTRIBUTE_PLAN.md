# MeterFlow Quality Attribute Plan (`docs/architecture/QUALITY_ATTRIBUTE_PLAN.md`)

## 1. Purpose

This document translates MeterFlow's five Quality Attributes (`QA1`–`QA5`, mapped in [`docs/REQUIREMENTS_TRACEABILITY.md`](../REQUIREMENTS_TRACEABILITY.md) and prioritized in [`UTILITY_TREE.md`](./UTILITY_TREE.md)) into concrete tactics, proven latency/freshness budgets, failure mitigations, and executable verification plans (`C3`, `C9`, `C10`).

---

## 2. Tactical Realization of `QA1`–`QA5`

### 2.1 `QA1` — Performance, Throughput & Proven One-Minute Freshness

| Workload / Requirement | Exact Target SLI | Architectural Tactics | Governing Artifacts |
| :--- | :--- | :--- | :--- |
| **Usage Event Ingestion (`FR1`)** | `p95 <= 200 ms`, `p99 <= 400 ms`, error rate `< 0.5%` at `100` VUs (`>= 500` events/s in batch up to `1,000` / concurrent load) | Caffeine `metricDefinitions` & `activeSubscriptionLookup`; atomic `ON CONFLICT (customer_id, idempotency_key) DO NOTHING RETURNING id` + synchronous `bucket_hour` `usage_rollups` upsert; Java Virtual Threads. | [ADR-001](./ADRs/ADR-001-modular-monolith-spring-modulith.md), [ADR-003](./ADRs/ADR-003-in-memory-caching-with-caffeine.md) |
| **Cached Catalog & Dashboard Reads (`FR2`, `FR3`, `FR10`)** | `p95 <= 150 ms` at `100` VUs; Caffeine hit ratio `>= 90%` | `60m` Caffeine `planVersions` (zero staleness because `PUBLISHED` versions are immutable) + `30s` `dashboardSummaries`. | [ADR-003](./ADRs/ADR-003-in-memory-caching-with-caffeine.md) |
| **Live Spend Estimate (`FR7`)** | **Freshness `<= 60 seconds` (`0.70s` typical / `15.65s` proven worst-case)**; query `p95 <= 250 ms` | Synchronous in-TX `bucket_hour` rollup (`0s` outbox & `0s` background aggregation delay) + after-commit Caffeine `liveEstimates` eviction (`0.05s`) bounded by **`15s` `expireAfterWrite` ceiling**; `UNIQUE_COUNT` queries indexed `usage_events` directly. | [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md#5-proven-one-minute--60s-live-spend-freshness-budget-fr7-qa1), [ADR-003](./ADRs/ADR-003-in-memory-caching-with-caffeine.md) |
| **Period-Close Batch Invoicing (`FR5`)** | `1,000` active subscriptions (`>= 100,000` events) billed in `<= 60 s`; heap `< 512 MB` | Two-step Spring Batch `chunk = 100` (`generateDraftInvoicesJob`, `finalizeInvoicesJob`), `reWriteBatchedInserts=true`, cached `planVersions`. | [ADR-005](./ADRs/ADR-005-batch-billing-and-aggregation-with-spring-batch.md) |

---

### 2.2 `QA2` — Financial Correctness, Reliability & Idempotency

| Integrity Hazard | Target Guarantee | Architectural Tactics | Governing Artifacts |
| :--- | :--- | :--- | :--- |
| **Duplicate & Conflicting Events (`FR1`)** | `0` duplicate inserts/rollups at `50`-VU concurrency; `100%` `409 Conflict` when same `idempotency_key` carries differing `payload_hash` | `uq_usage_events_customer_idempotency (customer_id, idempotency_key)` + canonical `SHA-256` `payload_hash`; idempotency check runs **before** the `72h` expiration gate. | [`DATA_MODEL.md`](./DATA_MODEL.md), [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md) |
| **Proration, `PER_SEAT` & Tier Rounding (`FR3`–`FR6`)** | `0.0000` discrepancy across lines, subtotals, tax, credit notes, and ledger | `NUMERIC(19,8)` intermediate proration/unit rates -> `NUMERIC(19,4)` `HALF_UP` per line (`subtotal = SUM(rounded lines)`); **financial billing never reads Caffeine `liveEstimates`**. | [`SYSTEM_DESIGN.md`](./SYSTEM_DESIGN.md#6-financial-calculation--strategycomposite-architecture-fr3fr6-fr10-qa2) |
| **Post-Finalization `72h` Late Events (`FR1`, `FR6`)** | Exact tier/allowance preservation without mutating `FINALIZED` invoices | Cumulative period re-rating (`delta = new_period_charge - prior_billed_charge`) in `late_usage_adjustments`; DB triggers lock `FINALIZED` invoices and `PUBLISHED` `plan_versions`. | [`DATA_MODEL.md`](./DATA_MODEL.md) |
| **Dunning Retry & Suspension Separation (`FR8`)** | Exactly **3** payment retries (Day 1, Day 3, Day 7); zero unintended 4th payment attempt | Separate `retry_offsets_hours = [24, 72, 168]` vs. `suspension_offset_hours` timer; `uq_captured_payment_per_invoice` prevents double capture. | [ADR-007](./ADRs/ADR-007-simulated-payment-processing-model.md) |
| **Outbox & Webhook Durability (`FR9`, `C8`)** | `0` lost internal events (`<= 30s` recovery); durable `SKIP LOCKED` exponential backoff (`30s`..`6h`) | Two-tier PostgreSQL durability: `event_publication` outbox + `webhook_deliveries` / `webhook_delivery_attempts`. | [ADR-004](./ADRs/ADR-004-postgresql-transactional-outbox-messaging.md), [ADR-009](./ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) |

---

### 2.3 `QA3` — Modifiability, Modularity & Open/Closed Extensibility

1. **Enforced `10`-Module DAG (`C1`)**: `ApplicationModules.of(MeterFlowApplication.class).verify()` executes on every `mvn test`.
2. **Open/Closed Strategy + Composite Pricing (`FR3`)**: Six concrete `ChargeCalculationStrategy` beans (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`) inside `catalog` handle all pricing across `FR5`/`FR6`/`FR7`/`FR10`; introducing a new charge type (e.g., an optional `PERCENTAGE` strategy) requires `0` edits to existing charge classes.
3. **Database Portability (`C5`)**: Switching between `local` (Docker Compose) and `supabase` PostgreSQL changes only Spring profiles and environment variables.

---

### 2.4 `QA4` — Security, Tenant Isolation & Cryptographic Webhook Integrity

1. **Portal & API Chains (`ADR-006`)**: `BCrypt` (`>= 10`), `100%` CSRF protection on stateful Thymeleaf forms, and principal-derived `customerId` scoping (`0` IDOR leaks on invoices, simulations, budgets, and webhook replays).
2. **Dual-Key `HMAC-SHA256` Signing & Replay Auth (`FR9`, `ADR-009`)**: Signing secrets encrypted at rest; rotation grace window signs with both active and previous secrets (`v1=<active>,v1=<prev>`); `<= 300s` skew check + constant-time verification; SSRF IP denylist; strict RBAC on manual replay (`ROLE_CUSTOMER` restricted to own endpoints, `409` on inactive endpoints).
3. **Zero Secrets in Git (`C6`)**: All DB passwords, encryption keys, and PATs remain externalized.

---

### 2.5 `QA5` — Testability, Reproducibility & Observability

1. **Injectable `java.time.Clock`**: Tests `72h` late cutoffs, proration splits, Day 1/3/7 dunning retries, separate suspension timers, `30s`..`6h` webhook backoff, and secret-rotation expiry deterministically in milliseconds.
2. **Offline Testcontainers CI (`ADR-008`)**: `./mvnw verify` runs against ephemeral `postgres:17-alpine` with zero cloud credentials.
3. **Telemetry**: Actuator `/actuator/health` + Micrometer Caffeine, HikariCP, batch, outbox, dunning, and webhook backlog metrics + MDC correlation IDs.

---

## 3. Quantitative Benchmarks & `k6` Load Suite (`C10`)

All `k6` workloads (`perf/k6/`) **must execute strictly against Local Docker Compose PostgreSQL** (never against shared Supabase PostgreSQL). Full-scale PostgreSQL 17 Testcontainers benchmarks are also executable directly via Maven (`QaScaleBenchmarkIT` and `QaEngineeringValidationIT`; see [`docs/TESTING_AND_BENCHMARK_REPORT.md`](../TESTING_AND_BENCHMARK_REPORT.md)).

| Implemented `k6` Script / Benchmark | Scenario | Primary Assertions | Requirements Verified |
| :--- | :--- | :--- | :--- |
| `perf/k6/qa1-one-million-reconciliation.js` | Deterministic `1,000,000`-event dataset (`95%` unique on-time, `3%` duplicates, `2%` valid late within `72h`) with independent mathematical sum/count reconciliation | `0` double counting; `POST /api/v1/metering/usage/query` & `/rollups/reconcile` match exact mathematical totals | `FR1`, `FR2`, `QA1`, `QA2`, `C10` |
| `perf/k6/qa2-throughput-latency.js` | `constant-arrival-rate` batched ingestion against `POST /api/v1/events/batch` paired with Micrometer ingestion/rollup timers | Effective throughput & `http_req_duration` latency separated from DB/rollup latency | `FR1`, `QA1`, `QA5`, `C10` |
| `perf/k6/qa3-restart-recovery.js` | Overlapping deterministic batches across a simulated `docker compose restart app`, retry with identical idempotency keys, and `/rollups/recover` + `/rollups/reconcile` | Duplicate-free recovery and exact post-restart aggregate parity | `FR1`, `QA2`, `C10` |
| `QaScaleBenchmarkIT` (opt-in Maven IT) | Local PostgreSQL 17 Testcontainers full-scale verification (`1,000,000` unique + `30k` dup + `20k` late events; `10,000`-customer Spring Batch 6 run) | **Verified**: `16,586.5 events/sec`, batch-250 `p95=46 ms`, `0.00` paisa drift, `10,000` customers billed in `5m 42.2s` (`0` duplicates on re-run) | `QA1`, `QA2`, `QA5`, `C10` |
