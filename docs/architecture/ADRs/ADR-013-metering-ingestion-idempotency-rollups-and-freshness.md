# ADR-013: Metering Ingestion Architecture, Database Idempotency, Incremental Rollups, and Usage Freshness

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-013` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR1`, `FR2`, `FR7`, `QA1`, `QA2`, `QA3`, `QA4`, `QA5`, `C1`, `C5`, `C7`, `C8`, `C9`, `C10` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow requires a high-throughput, financially accurate **Metering Engine** (`com.meterflow.metering`) capable of:

1. Ingesting batches of up to `1,000` usage events per HTTP request with strict customer authorization, partial batch acceptance, and high throughput (`QA2` target: `2,000` events/sec, `p95 <= 100 ms`).
2. Enforcing crash-safe, concurrency-safe idempotency at the PostgreSQL layer—acknowledging identical duplicates with zero double counting while rejecting reuse of an idempotency key with a materially different payload (`409 Conflict` / per-event `CONFLICT`).
3. Handling late-arriving usage events within a `72-hour` window after a billing period closes while preserving raw events immutably and surfacing late adjustments to downstream Billing without modifying historical finalized records (`FR1`).
4. Evaluating all five existing Catalogue aggregation types (`SUM`, `COUNT`, `MAXIMUM`, `UNIQUE_COUNT`, `LATEST_VALUE`) and five filter operators (`EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `EXISTS`) without duplicating `catalog.api.MetricEvaluationSupport` (`FR2`).
5. Delivering live usage estimates within a `<= 60 seconds` freshness budget alongside deterministic, deduplicated `50%`, `80%`, and `100%` usage threshold events (`FR7`), using PostgreSQL and bounded Caffeine only (zero Redis, zero Kafka).

---

## 2. Decision Drivers

- **Zero Double Counting & Database Authority (`QA1`, `QA2`)**: Idempotency and rollup integrity must survive concurrent duplicate batches, JVM crashes, and retries without relying on in-memory deduplication maps.
- **High Batch Throughput on PostgreSQL (`QA2`)**: Avoid N+1 queries, per-event transactions, pre-insert lookup round-trips on the happy path, and customer-wide locks.
- **Deterministic Aggregation Parity (`FR2`)**: Java and PostgreSQL ordering for `LATEST_VALUE` (`event_timestamp DESC, ingested_at DESC, CAST(id AS text) DESC`) and strict null/invalid numeric rejection must match `MetricEvaluationSupport` identically.
- **Strict Module Encapsulation (`C1`, `QA3`)**: `metering` interacts with `identity`, `catalog`, and `subscriptions` strictly through `@NamedInterface("api")` facades.
- **Distinct Quantity vs. Monetary Precision (`ADR-010`)**: Usage quantities require `NUMERIC(19,6)` (and `NUMERIC(24,6)` for cumulative sums) while monetary amounts remain `NUMERIC(19,4)` / `NUMERIC(19,2)` via `Money`.

---

## 3. Considered Options

### Option A — JPA Entity-per-Event `saveAll()` + Async Background Rollup Worker
- Rejected: JPA entity persistence with pre-insert `SELECT` checks generates excessive round-trips, Hibernate persistence-context memory overhead on `1,000`-event batches, and introduces background aggregation lag that threatens `FR7`'s `<= 60s` freshness budget and complicates crash reconciliation.

### Option B — Pure On-Demand Aggregation Over `usage_events` Without Rollups
- Rejected: Scanning hundreds of thousands of raw rows per customer dashboard refresh degrades `p95` read latency as event volumes approach `1,000,000+` events (`QA1`).

### Option C — Single-Round-Trip PostgreSQL `UNNEST` Batch `INSERT ... ON CONFLICT DO NOTHING RETURNING` + Transactional Hourly `usage_rollups` Upsert + Exact Edge/Reconciliation Queries (Selected)
- Selected: Combines O(1) SQL round-trips per batch, authoritative DB idempotency, atomic exact-once hourly rollups, deterministic deadlock-free bucket lock ordering, exact sub-hour edge + `UNIQUE_COUNT` fallback on indexed `usage_events`, and full reconciliation from immutable raw events.

---

## 4. Decision Outcome

### 4.1 Batch Ingestion & Database-Enforced Idempotency (`V5__metering_schema.sql`)
- `usage_events` enforces `CONSTRAINT uq_usage_events_customer_idempotency UNIQUE (customer_id, idempotency_key)` and persists a canonical `SHA-256` `payload_hash` computed over `(customerId, metricId, normalizedQuantity, utcMicrosEventTimestamp, recursivelySortedProperties)`.
- Validation executes in memory after batched lookups (`1` query for distinct customers, Caffeine-cached batch lookup for metrics, `1` query for open subscriptions). Partial batch acceptance is the authoritative policy: invalid events are recorded as `REJECTED` (and logged to `usage_rejected_events` without exposing sensitive payloads) while valid events in the same batch proceed.
- Valid events insert via a single PostgreSQL JDBC `UNNEST(...)` statement using `INSERT INTO usage_events (...) ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING RETURNING id, customer_id, idempotency_key`.
- Only keys omitted from `RETURNING` (conflicts/duplicates) trigger a single batched lookup against `usage_events` to compare `payload_hash`: identical hashes return `DUPLICATE` (`0` rollup delta); differing hashes return `CONFLICT` (`IDEMPOTENCY_KEY_CONFLICT`) and persist a `usage_rejected_events` audit record.

### 4.2 Late-Event Policy (`FR1`)
- Both `event_timestamp` and `ingested_at` are stored in UTC (`TIMESTAMPTZ`, microsecond precision).
- Idempotency lookup/conflict resolution always precedes temporal rejection so replaying a previously accepted historical key returns `DUPLICATE` rather than failing.
- Events with `event_timestamp > ingested_at + 5 minutes` are rejected (`FUTURE_TIMESTAMP`).
- Using `SubscriptionQueryFacade` (zero duplicated period math), when an event's `event_timestamp` precedes the customer's closed billing period boundary (`closedPeriodEnd`):
  - If `ingested_at <= closedPeriodEnd + 72 hours`: accepted as `ACCEPTED_LATE` (`LATE_WITHIN_72H`), persisted in `usage_events` and `usage_late_event_records` (`PENDING_BILLING`), and published via `LateUsageEventRecordedEvent` through Spring Modulith's transactional outbox.
  - If `ingested_at > closedPeriodEnd + 72 hours`: rejected (`LATE_WINDOW_EXCEEDED`).
- When no closed billing period boundary applies (open period or pre-subscription), events with `ingested_at - event_timestamp > 72 hours` are rejected (`LATE_WINDOW_EXCEEDED`); otherwise accepted as `ON_TIME`.

### 4.3 Aggregation, Transactional Rollups & Reconciliation (`FR2`, `QA1`, `QA5`)
- Filter matching and target-property extraction delegate directly to `catalog.api.MetricEvaluationSupport` (zero duplicated filter or validation logic). Invalid, null, blank, or non-numeric target properties fail explicitly during validation and are never coerced to zero.
- Only `RETURNING`-confirmed newly inserted rows with `filter_matched = true` are pre-aggregated in memory by `(customer_id, metric_id, bucket_start)` (UTC hour) and upserted into `usage_rollups` inside the **same ACID transaction**, sorted deterministically by `(customer_id, metric_id, bucket_start)` to prevent lock-order deadlocks.
- `LATEST_VALUE` updates use the exact tie-breaking tuple `(event_timestamp, ingested_at, CAST(id AS text))` matching `MetricEvaluationSupport.LATEST_VALUE_ORDER`.
- **Query Strategy**:
  - For `SUM`, `COUNT`, `MAXIMUM`, and `LATEST_VALUE` without ad-hoc extra runtime filters: full interior hours read from `usage_rollups` (`<= 744` rows/month), while partial leading/trailing sub-hour slices read from indexed `usage_events` (`idx_usage_events_cust_metric_ts`), combining deterministically for exact sub-second window accuracy.
  - For `UNIQUE_COUNT` (non-additive across buckets) and ad-hoc filtered queries: queries execute directly over indexed `usage_events` (`COUNT(DISTINCT distinct_key)` / `MetricEvaluationSupport` filter evaluation).
- **Reconciliation & Recovery**: `UsageReconciliationFacade.reconcileRollups(...)` recomputes `usage_rollups` directly from authoritative `usage_events` inside an ACID transaction, verifies exact parity, and marks events `RECONCILED`. Any event found in `PENDING_ROLLUP` after an interrupted process can be deterministically rolled up/reconciled without double counting.

### 4.4 Live Usage Estimates (`FR7`) & Threshold Event Contract
- `UsageQueryFacade.estimateLiveUsage(...)` integrates `SubscriptionQueryFacade`, `MetricCatalogFacade`, and `RatingEngineFacade` (published APIs only) and caches non-authoritative estimate snapshots in Caffeine `liveEstimates` (`expireAfterWrite = 15s`, `maximumSize = 5,000`).
- Every committed ingestion batch and rollup reconciliation evicts affected customer keys from `liveEstimates` via `TransactionSynchronization.afterCommit()`—proving `<= 15s` worst-case staleness (`<< 60s` target).
- Authoritative usage queries (`queryMetricUsage`, `reconcileRollups`, downstream Billing queries) **never** read Caffeine `liveEstimates`.
- `UsageThresholdFacade.evaluateThresholds(...)` evaluates `50%`, `80%`, and `100%` utilization against caller-supplied limits (never inventing budget values), persists deduplicated rows in `usage_threshold_evaluations` (`uq_usage_threshold_once` via `ON CONFLICT DO NOTHING`), and publishes `UsageThresholdReachedEvent` via Spring Modulith's transactional outbox for future `notifications` and `webhooks` consumption.

---

## 5. Consequences

### Positive
- Single-statement `UNNEST` batch ingestion + transactional pre-aggregated hourly rollups eliminate N+1 queries and support high throughput while preserving strict PostgreSQL ACID idempotency.
- Zero external infrastructure (no Redis, Kafka, or RabbitMQ) and zero duplicated Catalogue or Subscription logic.
- Full auditability and recomputation from immutable `usage_events` guarantee `QA1` reconciliation and `QA5` line-level traceability foundation.

### Tradeoffs & Constraints
- Synchronous transactional rollup upserts add a small bounded write cost per distinct `(customer_id, metric_id, hour)` bucket in a batch in exchange for `0s` aggregation lag and exact-once crash safety.
- `UNIQUE_COUNT` queries scan the indexed `(customer_id, metric_id, event_timestamp)` window on `usage_events` rather than approximating via HyperLogLog, prioritizing exact financial accuracy over approximate sketching.

---

## 6. Compliance and Verification

- Verified by `MeteringUnitTests` (all 5 aggregations, 5 filters, tie-breaking, payload hashing, `72h` boundaries, threshold evaluation, reconciliation math), `ModularityTests` (`metering :: api` encapsulation and zero cycles), and `MeteringModuleIT` (PostgreSQL 17 Testcontainers validating Flyway `V5`, Hibernate `ddl-auto=validate`, concurrent duplicate batches, conflicts, late events, rollups, reconciliation, customer isolation, and RFC 9457 errors).
- Performance and recovery verified via reproducible `k6` scripts (`perf/k6/`) and PostgreSQL benchmark evidence recorded truthfully in [`WORK_LOG.md`](../../WORK_LOG.md).

---

## 7. Related Documents

- [ADR-001](./ADR-001-modular-monolith-spring-modulith.md)
- [ADR-002](./ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md)
- [ADR-003](./ADR-003-in-memory-caching-with-caffeine.md)
- [ADR-004](./ADR-004-postgresql-transactional-outbox-messaging.md)
- [ADR-010](./ADR-010-financial-precision-currency-and-rounding-policy.md)
- [ADR-011](./ADR-011-pricing-tiers-packages-allowances-and-commitments.md)
- [ADR-012](./ADR-012-subscription-workflow-billing-periods-and-proration.md)
- [DATA_MODEL.md](../DATA_MODEL.md)
- [MODULE_BOUNDARIES.md](../MODULE_BOUNDARIES.md)
- [SYSTEM_DESIGN.md](../SYSTEM_DESIGN.md)
