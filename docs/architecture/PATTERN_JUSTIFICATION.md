# MeterFlow Architectural Pattern Justification (`docs/architecture/PATTERN_JUSTIFICATION.md`)

## 1. Purpose

This document justifies MeterFlow's architectural and design patterns against functional complexity (`FR1`–`FR10`), quality targets (`QA1`–`QA5`), and strict project rules (`C1`–`C12`: **no Redis, no Kafka, no paid cloud services or API keys, no real payment gateway, no React/Next.js frontend, no unnecessary microservices, no Kubernetes, and no duplicate business logic**).

---

## 2. Core Patterns & Rejected Alternatives

### 2.1 Domain-Partitioned Modular Monolith (`Spring Modulith`)

- **Selected Pattern**: Ten-module **Spring Modulith** modular monolith verified by `ApplicationModules.verify()` ([ADR-001](./ADRs/ADR-001-modular-monolith-spring-modulith.md)).
- **Rejected Alternatives**:
  - *Microservices / Kubernetes*: Prohibited (`C7`); adds distributed saga complexity across invoicing, credits, and dunning.
  - *Unstructured Layered Monolith*: Fails `C1`/`QA3`; allows cross-domain repository coupling and `billing <-> payments` cycles.

---

### 2.2 Strategy + Composite Pricing Engine (`FR3`, `FR5`, `FR6`, `FR7`, `FR10`, `QA3`)

- **Problem**: `FR3` mandates six distinct charge types—`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, and **`PER_SEAT`**—combined with free allowances, mid-cycle proration (`FR4`), and charge/plan minimum commitments, AND must support adding a new instructor-defined charge type (such as an optional `PERCENTAGE` model) without editing existing calculation classes, while avoiding duplicate business logic across invoicing, late adjustments, live estimates, and simulations.
- **Selected Pattern**: **Strategy Pattern** (`ChargeCalculationStrategy` interface + `ChargeStrategyRegistry`) composed inside a **Composite** `PlanRatingComposite` (`RatingEngineFacade` in `catalog`):
  - **Six Concrete Strategies**: `FlatFeeChargeStrategy`, `PerUnitChargeStrategy`, `GraduatedTiersChargeStrategy`, `VolumeTiersChargeStrategy`, `PackageChargeStrategy`, and `PerSeatChargeStrategy` (deterministic seat calculation from `subscription_segments.seat_quantity` or active-seat metered gauges).
  - **Composite Orchestration**: Applies pre-charge free-allowance deduction (`MAX(0, raw_qty - effective_free_units)`), `NUMERIC(19,8)` segment proration, charge strategy invocation, and charge/plan minimum-commitment true-up (`MINIMUM_COMMITMENT_TRUE_UP`).
  - **Open/Closed Principle (OCP)**: Adding any future charge type requires implementing one new `@Component` `ChargeCalculationStrategy` with parameters stored in `plan_charges.charge_config JSONB`—**zero modifications to existing strategy classes or `PlanRatingComposite`**.
- **Rejected Alternative**:
  - *Central `switch (chargeType)` Monolithic Method*: Violates OCP—every new charge type modifies and risks regressing all six tested charge models.

---

### 2.3 Synchronous Hourly Rollups + `15s` After-Commit Caffeine Cache (`FR7`, `QA1`, `C7`)

- **Problem**: `FR7` requires live usage and estimated charges refreshed within **one minute (`<= 60s`)** without Redis (`C7`) and without risking financial inaccuracy from stale caches.
- **Selected Pattern**: Synchronous in-transaction `usage_rollups (subscription_id, metric_id, bucket_hour)` upsert during ingestion (`0s` outbox/background lag; exact `UNIQUE_COUNT` queried directly on indexed `usage_events`) paired with a `15s`-TTL **Caffeine** `liveEstimates` cache evicted via after-commit callback (`0.70s` typical / `15.65s` proven worst-case freshness `<< 60s`). Authoritative invoicing (`FR5`, `FR6`) never reads Caffeine `liveEstimates` ([ADR-003](./ADRs/ADR-003-in-memory-caching-with-caffeine.md)).
- **Rejected Alternatives**:
  - *`60s` Background Aggregation Job + `30s` Cache TTL*: Worst-case freshness exceeds `90 seconds`, **violating `FR7`**!
  - *Redis*: Prohibited (`C7`).

---

### 2.4 Two-Tier PostgreSQL Transactional Outbox & `SKIP LOCKED` Queues (`FR1`, `FR8`, `FR9`, `C7`, `C8`)

- **Selected Pattern**:
  - **Tier 1 (`event_publication`)**: Atomic internal domain events via `spring-modulith-starter-jpa` (`15s` sweeper, `<= 30s` recovery) ([ADR-004](./ADRs/ADR-004-postgresql-transactional-outbox-messaging.md)).
  - **Tier 2 (`webhook_deliveries` & `dunning_cases`)**: PostgreSQL `FOR UPDATE SKIP LOCKED` queues handling multi-hour `HMAC-SHA256` webhook exponential backoff (`30s`..`6h`, dual-key secret rotation, attempt history, authorized replay) and exact Day 1/3/7 dunning retries + separate suspension transition timer ([ADR-007](./ADRs/ADR-007-simulated-payment-processing-model.md), [ADR-009](./ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md)).
- **Rejected Alternatives**:
  - *Apache Kafka / RabbitMQ / Paid Webhook SaaS*: Prohibited (`C7`).
  - *Blocking External HTTP Calls Inside Internal Outbox Listeners*: Starves internal billing/dunning listeners during third-party outages.

---

### 2.5 Two-Step Chunked Spring Batch (`DRAFT` -> `FINALIZE`) & Server-Rendered Thymeleaf (`FR5`, `FR8`, `C7`)

- **Selected Patterns**:
  - Two-step **Spring Batch 6** (`generateDraftInvoicesJob` + `finalizeInvoicesJob`, `chunk = 100`) harmonizing `FR1`'s `72h` late grace window with `FR5` `FINALIZED` immutability ([ADR-005](./ADRs/ADR-005-batch-billing-and-aggregation-with-spring-batch.md)).
  - **Spring MVC + Thymeleaf + Dual Spring Security 7 Chains** + internal deterministic `SimulatedPaymentService` ([ADR-006](./ADRs/ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md), [ADR-007](./ADRs/ADR-007-simulated-payment-processing-model.md)).
- **Rejected Alternatives**: Separate React/Next.js SPA and real payment gateways (explicitly prohibited by `C7`).

---

## 3. Pattern-to-Requirement Synergy Matrix

| Pattern | `FR` Addressed | `QA` Reinforced | `C` Satisfied | ADRs |
| :--- | :--- | :--- | :--- | :--- |
| **Spring Modulith `10`-Module DAG** | `FR1`–`FR10` | `QA3`, `QA5` | `C1`, `C7`, `C9` | [ADR-001](./ADRs/ADR-001-modular-monolith-spring-modulith.md) |
| **Strategy + Composite `RatingEngine` (`6` Types)** | `FR3`, `FR5`–`FR7`, `FR10` | `QA2`, `QA3` | `C1`, `C7` | [ADR-001](./ADRs/ADR-001-modular-monolith-spring-modulith.md) |
| **Flyway Dual PostgreSQL (`Docker` + `Supabase`)** | `FR1`–`FR10` | `QA2`, `QA3`, `QA5` | `C5`, `C7`, `C11` | [ADR-002](./ADRs/ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md) |
| **Sync Hourly Rollups + `15s` Caffeine Cache** | `FR2`, `FR3`, `FR7`, `FR10` | `QA1`, `QA2` | `C7`, `C10` | [ADR-003](./ADRs/ADR-003-in-memory-caching-with-caffeine.md) |
| **Two-Tier PostgreSQL Outbox & `SKIP LOCKED` Queues** | `FR1`, `FR6`–`FR9` | `QA2`–`QA4` | `C6`–`C9` | [ADR-004](./ADRs/ADR-004-postgresql-transactional-outbox-messaging.md), [ADR-009](./ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) |
| **Two-Step Chunked Spring Batch (`DRAFT` -> `FINALIZE`)** | `FR1`, `FR5`, `FR6` | `QA1`, `QA2`, `QA5` | `C5`, `C9` | [ADR-005](./ADRs/ADR-005-batch-billing-and-aggregation-with-spring-batch.md) |
| **Thymeleaf MVC + Simulated Payment & Day 1/3/7 Dunning** | `FR1`–`FR10` | `QA2`, `QA4`, `QA5` | `C6`, `C7`, `C9` | [ADR-006](./ADRs/ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md), [ADR-007](./ADRs/ADR-007-simulated-payment-processing-model.md) |
