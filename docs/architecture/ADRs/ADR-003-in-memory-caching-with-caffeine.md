# ADR-003: Bounded In-Memory Caching with Caffeine, Sync Hourly Rollups, and Proven One-Minute Freshness Instead of Redis

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-003` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR2`, `FR3`, `FR7`, `FR10`, `QA1`, `QA2`, `QA5`, `C7`, `C10` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow requires sub-millisecond reads for metric definitions (`FR2`), immutable published pricing plan versions (`FR3`, `FR10`), active subscription lookups (`FR1`, `FR4`), and dashboard KPIs, while **mathematically proving `<= 60 seconds` (`1 minute`) worst-case freshness on live usage and estimated charges (`FR7`)**—strictly using **Caffeine** with **no Redis and no Kafka** (`C7`), and ensuring **financial correctness never depends on stale cache values** (`QA2`).

A naive design pairing a `60-second` background aggregation sweep with a `30-second` cache TTL yields `> 90 seconds` worst-case lag and violates `FR7`.

---

## 2. Decision Drivers

- **Strict Redis & Kafka Prohibition (`C7`)**: All caching runs in-process via **Caffeine**; all durability runs in PostgreSQL.
- **Proven `<= 60s` Freshness Budget (`FR7`, `SC-QA1-2`)**: Account explicitly for ingestion, publication, aggregation, cache invalidation, and read latencies.
- **Hot-Path Read Performance (`QA1`, `SC-QA1-1`, `SC-QA1-3`)**: Sustain `p95 <= 200 ms` ingestion, `p95 <= 150 ms` cached reads (`>= 90%` hit ratio), and `p95 <= 250 ms` live estimates.
- **Zero Financial Dependence on Cache (`QA2`)**: Authoritative invoices (`FR5`), credit notes (`FR6`), and late adjustments (`FR6`) must read committed PostgreSQL tables directly.

---

## 3. Considered Options

### Option A: Bounded Caffeine Caches (`15s` `liveEstimates` TTL + After-Commit Eviction) Backed by Synchronous In-Transaction `bucket_hour` Rollups
- **Pros**: Eliminates outbox (`0s`) and background aggregation (`0s`) lag for live spend; proves **`0.70 s` typical / `15.65 s` worst-case freshness (`<= 60s` with `44.35s` margin)**; `PUBLISHED` `plan_versions` are immutable so `planVersions` (`60m` TTL) has zero staleness; native Micrometer `recordStats()`.
- **Cons**: Adds one lightweight indexed `UPSERT` to `usage_rollups` inside each accepted ingestion transaction (skipped on duplicates/conflicts).

### Option B: Periodic `60s` Background Rollup Job + `30s` Caffeine TTL
- **Pros**: Decouples rollup writes from ingestion TX.
- **Cons**: **Fails `FR7`** — worst-case freshness = `0.4s` ingest + `60s` rollup wait + `2s` compute + `30s` TTL = `92.4 seconds > 60 seconds`.

### Option C: Distributed Redis Cache
- **Pros**: Shared multi-node invalidation.
- **Cons**: **Explicitly prohibited** (`No Redis`, `C7`).

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Bounded In-Process Caffeine Caching Paired with Synchronous Hourly Rollups and After-Commit Eviction**.

### Configured Cache Regions (`record` DTOs Only)
1. `planVersions`: `maximumSize=1000, expireAfterWrite=60m, recordStats` (immutable `PUBLISHED` `PlanVersionBundleDTO` — zero staleness).
2. `metricDefinitions`: `maximumSize=1000, expireAfterWrite=15m, recordStats` (after-commit eviction on filter/metric edits).
3. `activeSubscriptionLookup`: `maximumSize=5000, expireAfterWrite=5m, recordStats` (after-commit eviction on trial/plan/seat/dunning transitions).
4. `liveEstimates`: `maximumSize=5000, expireAfterWrite=15s, recordStats` (after-commit key eviction on local ingestion or subscription change; `15s` TTL acts as hard ceiling).
5. `dashboardSummaries`: `maximumSize=200, expireAfterWrite=30s, recordStats`.

### Worst-Case Freshness Budget (`FR7`)
- `T_ingest (p99)` `0.40 s` + `T_publish` `0.00 s` (no outbox wait) + `T_aggregate` `0.00 s` (sync `bucket_hour` upsert; `UNIQUE_COUNT` queries indexed `usage_events` directly) + `T_invalidate` `0.05 s` normal / `15.00 s` TTL cap + `T_read (p95)` `0.25 s` = **`0.70 s` typical / `15.65 s` worst-case (`<= 60.00 s`)**.
- **Authoritative Billing Separation**: `billing` (`FR5`, `FR6`) never queries Caffeine `liveEstimates`; it reads committed PostgreSQL data inside ACID transactions.

---

## 5. Consequences

### Positive Consequences
- Mathematically proves `FR7` `1-minute` freshness while honoring `C7` (`No Redis`, `No Kafka`) and `QA2` financial safety.

### Negative Consequences and Trade-Offs
- Single-JVM deployment assumption (**Assumption A1**); even under multi-node burst where another node skips local eviction, the `15s` hard TTL still satisfies `<= 60s` (`15.65s` worst-case).

### Risks and Mitigations
- **Risk**: Caching JPA entities causes `LazyInitializationException` or thread-unsafe mutation.
  - **Mitigation**: Store exclusively immutable Java `record` DTOs across all five Caffeine regions.

---

## 6. Compliance and Verification

- `pom.xml` includes `caffeine` and zero `redis`/`lettuce`/`jedis` artifacts (`C7`).
- Integration test ingests usage events and asserts updated live estimate & `usage.threshold_reached` alert strictly within `< 60s` (`SC-QA1-2`); `k6` validates `>= 90%` hit ratio (`C10`).

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md#5-proven-one-minute--60s-live-spend-freshness-budget-fr7-qa1)
- [`QUALITY_ATTRIBUTE_PLAN.md`](../QUALITY_ATTRIBUTE_PLAN.md)
- [`UTILITY_TREE.md`](../UTILITY_TREE.md)
