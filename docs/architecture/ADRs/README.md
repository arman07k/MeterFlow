# MeterFlow Architecture Decision Records (`docs/architecture/ADRs/README.md`)

## 1. Purpose & Governance

This directory maintains the authoritative **Architecture Decision Records (ADRs)** for **MeterFlow** (`C2`). Accepted ADRs govern all module boundaries, schemas, caches, batch jobs, payment/dunning behaviors, and webhook protocols. Any future architectural revision requires stakeholder approval and a superseding ADR per [`AGENT.md`](../../../AGENT.md).

---

## 2. Accepted ADR Index

| ADR ID | Title | Status | Related Requirements | Summary Decision |
| :--- | :--- | :--- | :--- | :--- |
| [ADR-001](./ADR-001-modular-monolith-spring-modulith.md) | Modular Monolith Architecture with Spring Modulith and Strategy/Composite Rating Engine on Spring Boot 4 / Java 25 | Accepted | `FR1`–`FR10`, `QA1`, `QA3`, `QA5`, `C1`, `C7` | Partition MeterFlow into 10 acyclic Spring Modulith modules on Spring Boot 4.x / Java 25 (`-Pjava21`) with an OCP Strategy/Composite `RatingEngine` supporting all 6 mandatory charge types (`FLAT_FEE`..`PER_SEAT`). |
| [ADR-002](./ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md) | Dual PostgreSQL Persistence Strategy (Supabase & Docker Compose) with Spring Data JPA and Flyway | Accepted | `FR1`–`FR10`, `QA2`, `QA3`, `QA5`, `C5`, `C6`, `C11` | Govern all domain, batch, outbox, and webhook tables via Flyway (`ddl-auto=validate`) across Local Docker Compose (`local`) and Shared Supabase PostgreSQL (`supabase`). |
| [ADR-003](./ADR-003-in-memory-caching-with-caffeine.md) | Bounded In-Memory Caching with Caffeine, Sync Hourly Rollups, and Proven One-Minute Freshness Instead of Redis | Accepted | `FR2`, `FR3`, `FR7`, `FR10`, `QA1`, `QA5`, `C7`, `C10` | Use bounded Caffeine caches (`planVersions`, `metricDefinitions`, `activeSubscriptionLookup`, `liveEstimates` `15s` TTL, `dashboardSummaries`) proving `<= 15.65s` worst-case freshness (`<< 60s`); strictly prohibit Redis. |
| [ADR-004](./ADR-004-postgresql-transactional-outbox-messaging.md) | Reliable Internal Domain Messaging via PostgreSQL-Backed Transactional Outbox Instead of Kafka | Accepted | `FR1`, `FR4`, `FR6`–`FR9`, `QA2`, `QA3`, `C7`, `C8` | Persist cross-module events atomically in PostgreSQL `event_publication` via `spring-modulith-starter-jpa` with scheduled retry; strictly prohibit Kafka and external brokers. |
| [ADR-005](./ADR-005-batch-billing-and-aggregation-with-spring-batch.md) | Two-Step Chunk-Oriented Draft and Finalize Batch Invoicing with Spring Batch | Accepted | `FR1`, `FR5`, `FR6`, `QA1`, `QA2`, `QA5`, `C5`, `C9` | Execute period-close `generateDraftInvoicesJob` (accommodating the `72h` late-event window) and `finalizeInvoicesJob` via restartable Spring Batch 6 chunk pipelines. |
| [ADR-006](./ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md) | Server-Rendered Web Portal with Spring MVC, Thymeleaf, and Dual Spring Security Chains | Accepted | `FR1`–`FR10`, `QA4`, `QA5`, `C6`, `C7`, `C9` | Serve Admin, Operator, and Customer workflows via Spring MVC + Thymeleaf + Spring Security 7 (`BCrypt`, RBAC, CSRF, tenant scoping) alongside `/api/v1/events`; prohibit React/Next.js SPAs. |
| [ADR-007](./ADR-007-simulated-payment-processing-model.md) | Deterministic Internal Simulated Payment Collection, Day 1/3/7 Retry Schedule, and Separate Suspension Dunning Workflow | Accepted | `FR8`, `QA2`, `QA5`, `C7`, `C8`, `C9` | Model simulated collection, exactly three payment retries (`+24h`, `+72h`, `+168h`), separate suspension transition timer, customer notifications at every step, and recovery without real payment gateways. |
| [ADR-008](./ADR-008-verification-strategy-testcontainers-modulith-k6-ci.md) | Multi-Layer Verification with JUnit 5, Injectable Clock, Testcontainers, k6, and GitHub Actions | Accepted | `QA1`–`QA5`, `C1`, `C9`–`C12` | Verify domain math, module boundaries, PostgreSQL constraints, time-stepped workflows, and load SLIs via JUnit 5, Testcontainers, `k6`, and GitHub Actions. |
| [ADR-009](./ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) | Outbound HMAC-SHA256 Webhooks, Dual-Key Rotation, Exponential Backoff, History, and Authorized Replay in PostgreSQL | Accepted | `FR9`, `QA2`, `QA4`, `QA5`, `C6`, `C7`, `C8`, `C9` | Deliver `invoice.finalised`, `payment.failed`, and `usage.threshold_reached` via PostgreSQL `SKIP LOCKED` queue with dual-key `HMAC-SHA256` signing, exponential backoff, persistent history, and RBAC-scoped replay. |
| [ADR-010](./ADR-010-financial-precision-currency-and-rounding-policy.md) | Financial Precision, Currency Support, and Rounding Policy | Accepted | `FR3`–`FR8`, `FR10`, `QA2`, `C1`, `C9` | Restrict currency kernel to `BDT` and `USD` (no implicit FX), preserve exact unrounded `BigDecimal` intermediate math, enforce explicit `scale=8` / `scale=4` / minor-unit (`scale=2` paisa/cent) `HALF_UP` rounding, scale-insensitive equality, and plain-decimal JSON serialization. |
| [ADR-011](./ADR-011-pricing-tiers-packages-allowances-and-commitments.md) | Pricing Engine Tier, Package, Free Allowance, and Minimum Commitment Semantics | Accepted | `FR2`, `FR3`, `FR10`, `QA2`, `QA3`, `C1`, `C5`, `C9` | Formalize continuous `(lower, upper]` tiers (`GRADUATED_TIERS` vs. `VOLUME_TIERS`), ceiling partial-package rounding, single-consumption free allowances, non-duplicative commitments, string-keyed OCP strategy registry, and PostgreSQL published-version immutability triggers. |
| [ADR-012](./ADR-012-subscription-workflow-billing-periods-and-proration.md) | Subscription State Transitions, Billing-Period Semantics, and Proration Policy | Accepted | `FR4`, `FR3`, `FR10`, `QA2`, `QA3`, `QA4`, `C1`, `C5`, `C9` | Formalize single-open-subscription constraint, explicit state-transition policy, trial expiry/conversion rules, calendar-clamped zoned billing periods, exact-seconds UTC proration (`EXACT_SECONDS_V1`) over recurring charges only, and restart-safe PostgreSQL scheduled execution. |
| [ADR-013](./ADR-013-metering-ingestion-idempotency-rollups-and-freshness.md) | Metering Ingestion Architecture, Database Idempotency, Incremental Rollups, and Usage Freshness | Accepted | `FR1`, `FR2`, `FR7`, `QA1`–`QA5`, `C1`, `C5`, `C7`–`C10` | Standardize single-round-trip `UNNEST` batch ingestion with `(customer_id, idempotency_key)` + `SHA-256` `payload_hash`, `72h` late-event tracking, transactional hourly `usage_rollups` with exact edge fallback & reconciliation, `15s` after-commit Caffeine `liveEstimates`, and deduplicated `50/80/100%` threshold events. |
| [ADR-014](./ADR-014-billing-invoicing-tax-credits-and-batch-execution.md) | Invoicing, Tax Policy, Prepaid Credits, Credit Notes, Late Adjustments, and Spring Batch Execution | Accepted | `FR5`, `FR6`, `FR10`, `QA1`–`QA5`, `C1`, `C5`, `C7`–`C9` | Standardize two-step `DRAFT` -> immutable `FINALIZED` invoicing with PostgreSQL triggers, line-level provenance, extensible deterministic tax policies, pessimistic-locked FIFO prepaid credits, partial/full credit notes, cumulative-delta late adjustments, and restartable Spring Batch 6 chunk jobs. |
| [ADR-015](./ADR-015-payments-mock-processor-dunning-retries-and-suspension.md) | Payments & Dunning Engine — Deterministic Mock Processor, Day 1/3/7 Retries, Separate Suspension Timer, and ACID Settlement | Accepted | `FR8`, `FR9`, `QA2`–`QA5`, `C1`, `C5`–`C9` | Standardize deterministic mock payment outcomes, DB-enforced single captured settlement per invoice, append-only payment/dunning audit triggers, exact Day 1/3/7 retries with separate suspension timer, `SKIP LOCKED` worker, synchronous ACID billing/subscription settlement hooks, and Modulith outbox events. |

---

## 3. Mandatory Template for Future ADRs

Every ADR in `docs/architecture/ADRs/ADR-NNN-<kebab-case-title>.md` must include the metadata header table and all seven numbered sections below:

```markdown
# ADR-NNN: <Concise Decision Title>

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-NNN` |
| **Status** | `Proposed` \| `Accepted` \| `Deprecated` \| `Superseded` |
| **Date** | `YYYY-MM-DD` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `<FR*, QA*, C*>` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

## 1. Context and Problem Statement
## 2. Decision Drivers
## 3. Considered Options
## 4. Decision Outcome
## 5. Consequences
## 6. Compliance and Verification
## 7. Related Documents
```
