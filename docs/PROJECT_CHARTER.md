# MeterFlow Project Charter (`docs/PROJECT_CHARTER.md`)

## 1. Executive Summary & Problem Statement

Modern SaaS, API, and cloud-infrastructure providers bill customers based on granular consumption events combined with recurring subscriptions, tiered pricing models, prepaid credits, mid-cycle upgrades, spend budgets, automated dunning, and signed outbound webhooks. Building this pipeline naively leads to lost or double-counted events, floating-point rounding defects, mutated historical invoices, stale pricing mutations breaking active contracts, and expensive distributed infrastructure.

**MeterFlow** is a self-contained, financially rigorous **Usage-Based Metering, Subscription Rating, Invoicing, Dunning, and Webhook Platform** engineered as a **Spring Modulith** modular monolith on **Spring Boot 4.x** and **Java 25** (with **Java 21 LTS** fallback). It achieves strong ACID financial integrity, sub-minute live cost visibility, restartable batch invoicing, and signed webhook delivery using **PostgreSQL** (**Supabase PostgreSQL** for shared collaboration and **Docker Compose PostgreSQL** for local execution), **Caffeine** in-memory caching, **Spring Batch**, a **PostgreSQL-backed transactional outbox**, and **Spring MVC + Thymeleaf**—with **zero** Redis, Kafka, paid cloud APIs, external payment gateways, Kubernetes, or separate React/Next.js frontends.

---

## 2. Stakeholders & Actors

| Actor / Role | Security Role | Core Responsibilities in MeterFlow |
| :--- | :--- | :--- |
| **Platform Administrator** | `ROLE_ADMIN` | Defines billable metrics (`FR2`), publishes immutable pricing plan versions (`FR3`, `FR10`), configures tax/dunning policies (`FR5`, `FR8`), triggers/monitors Spring Batch billing jobs, and audits system health. |
| **Billing & Revenue Operator** | `ROLE_OPERATOR` | Manages customer accounts and subscriptions (`FR4`), previews/finalizes draft invoices (`FR5`), issues credit grants and credit notes (`FR6`), runs dry-run invoice simulations (`FR10`), monitors dunning cases (`FR8`), and inspects/replays webhook deliveries (`FR9`). |
| **Customer Account Holder** | `ROLE_CUSTOMER` | Views active subscriptions, live usage & cost estimates (`<= 1 min` freshness), receives usage threshold alerts (`FR7`), inspects itemized invoices and credit notes (`FR5`, `FR6`), views read-only payment & dunning status (`FR8`), manages HMAC webhook endpoints (`FR9`), and runs read-only historical simulations against `PUBLISHED` plans (`FR10`). *(Implementation note: mock payment initiation is restricted to `ADMIN`/`OPERATOR` staff per `ADR-015`, and standalone customer-editable spend-budget CRUD is not implemented; see [`REQUIREMENTS_TRACEABILITY.md`](./REQUIREMENTS_TRACEABILITY.md).)* |
| **Ingestion Producer / Client** | API Key / Role Principal | Sends idempotent single or batch usage events (`POST /api/v1/events`, `POST /api/v1/events/batch`) including backdated events within the `72-hour` window (`FR1`). |
| **Customer Webhook Receiver** | External HTTP Consumer | Verifies `X-MeterFlow-Signature` (`HMAC-SHA256`) headers on billing, budget, and dunning webhook payloads (`FR9`). |

---

## 3. Scope Definition

### 3.1 Functional Capabilities (`FR1`–`FR10`)

Canonical acceptance criteria, module mappings, and verification gates are maintained in [`docs/REQUIREMENTS_TRACEABILITY.md`](./REQUIREMENTS_TRACEABILITY.md):

- **`FR1` — Usage Event Ingestion, Idempotency Key Semantics & 72-Hour Late-Event Handling**: Deduplicated single/batch (`<= 1,000`) ingestion scoped by `(customer_id, idempotency_key)` + `SHA-256` `payload_hash` (`200` duplicate / `409` conflict), plus deterministic `72-hour` late-event routing (open/`DRAFT` inclusion or `FR6` post-finalization cumulative-delta adjustment; `> 72h` rejected and audited).
- **`FR2` — Billable Metrics (5 Aggregation Types & Configurable Filters)**: `COUNT`, `SUM`, `MAX`, `UNIQUE_COUNT`, and `LATEST` aggregations with configurable dimensional `JSONB` property filters.
- **`FR3` — Pricing Plans (6 Mandatory Charge Types, Free Allowances & Minimum Commitments)**: `FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, and **`PER_SEAT`** charges via an Open/Closed Strategy + Composite `RatingEngine` (with optional future extensions like `PERCENTAGE` requiring zero edits to existing charge classes), plus free allowances and minimum spend true-ups.
- **`FR4` — Subscriptions, Trials, Mid-Cycle Changes & Proration**: Trial windows, subscription state transitions, and mid-cycle plan or `seat_quantity` changes with exact `NUMERIC(19,8)` time-proportional segment proration.
- **`FR5` — Draft & Final Invoices, Tax & Line-Level Traceability**: `DRAFT` to immutable `FINALIZED` workflow, deterministic tax calculation, and full provenance links from each invoice line back to subscription segment, plan version, charge, seat quantity, metric window, and tier breakdown.
- **`FR6` — Credits, Credit Notes & Late Adjustments**: Priority/expiry-ordered prepaid/promotional credit wallets, immutable post-finalization credit notes, and cumulative-delta late-event adjustments.
- **`FR7` — Live Estimates (`<= 1 Minute` Proven Freshness), Budgets & Alerts**: Synchronous hourly `usage_rollups` + `15s`-TTL after-commit Caffeine `liveEstimates` (`0.70s` typical / `15.65s` worst-case freshness), spend budgets, and single-fire `usage.threshold_reached` alerts.
- **`FR8` — Simulated Payment Collection, Exact Day 1 / Day 3 / Day 7 Retry Schedule & Dunning Workflow**: Deterministic internal payment simulator, exactly three payment retries (`+24h`, `+72h`, `+168h`), separate suspension transition timer, customer notifications at every transition, and recovery at any applicable point.
- **`FR9` — Outbound Webhooks (`invoice.finalised`, `payment.failed`, `usage.threshold_reached`)**: Dual-key `HMAC-SHA256` signing with safe secret rotation, PostgreSQL exponential backoff retries, immutable delivery attempt logs, and RBAC-scoped manual replay.
- **`FR10` — Immutable Plan Versions & Dry-Run Invoice Simulation**: Read-only published `plan_versions` protecting active contracts, plus `@Transactional(readOnly = true)` "what-if" invoice simulation reusing the single canonical `RatingEngine`.

### 3.2 Strict Out-of-Scope Boundaries & Prohibitions

| Prohibited Item | Enforced Architectural Replacement |
| :--- | :--- |
| **Redis / External Distributed Cache** | Bounded in-process **Caffeine** caches ([ADR-003](./architecture/ADRs/ADR-003-in-memory-caching-with-caffeine.md)). |
| **Apache Kafka / RabbitMQ / External Brokers** | **PostgreSQL-backed transactional outbox** + PostgreSQL webhook/dunning queues ([ADR-004](./architecture/ADRs/ADR-004-postgresql-transactional-outbox-messaging.md), [ADR-009](./architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md)). |
| **Paid Cloud Services / Paid API Keys** | `100%` open-source/free stack; local Docker Compose PostgreSQL + free-tier shared Supabase PostgreSQL. |
| **Real Payment Gateway Integrations** | Deterministic internal **Simulated Payment** engine ([ADR-007](./architecture/ADRs/ADR-007-simulated-payment-processing-model.md)). |
| **React / Next.js / Separate SPA Frontend** | Server-rendered **Spring MVC + Thymeleaf** inside `portal` ([ADR-006](./architecture/ADRs/ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md)). |
| **Microservices / Kubernetes / Service Mesh** | Single deployable **Spring Modulith** modular monolith ([ADR-001](./architecture/ADRs/ADR-001-modular-monolith-spring-modulith.md)). |
| **Duplicate Business Logic** | Single pure `RatingEngine` in `catalog` shared across batch billing (`FR5`), late adjustments (`FR6`), live estimates (`FR7`), and simulations (`FR10`). |

---

## 4. Dual PostgreSQL Environment Strategy

MeterFlow operates identically across two PostgreSQL environments without code or SQL edits ([ADR-002](./architecture/ADRs/ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md), `C5`):

1. **Local Development & Load Benchmarks (`local` profile — Docker Compose PostgreSQL)**:
   - Runs locally via `compose.yaml` (`localhost:5432`, HikariCP `maximum-pool-size: 20`).
   - Primary environment for daily offline coding, deterministic debugging, and **all `k6` load tests** (`C10`).
   - Automated CI and `mvn verify` integration suites spin up isolated PostgreSQL instances via **Testcontainers** (`C9`).
2. **Shared Team Database (`supabase` profile — Supabase PostgreSQL)**:
   - Connects via externalized environment variables (`SUPABASE_DB_URL`, `SUPABASE_DB_USERNAME`, `SUPABASE_DB_PASSWORD`).
   - Uses conservative HikariCP sizing (`maximum-pool-size: 6`) to respect free-tier connection caps, and runs **Flyway** exclusively over Direct / Supavisor **Session Mode** (`5432`) so `pg_advisory_lock` succeeds reliably.

---

## 5. Explicit Architectural Assumptions

| ID | Assumption | Impacted Modules & Docs | Verification Rule |
| :--- | :--- | :--- | :--- |
| **A1** | **Single-Node Modular Monolith Deployment**: MeterFlow runs as one JVM instance in local and shared evaluation environments, allowing in-process **Caffeine** caches (`15s` TTL on `liveEstimates`; `60m` TTL on immutable `planVersions`) to remain consistent without cross-node invalidation buses. | `catalog`, `metering`, `portal`, [ADR-003](./architecture/ADRs/ADR-003-in-memory-caching-with-caffeine.md) | Verified via cache eviction & `1-minute` freshness integration tests (`FR7`, `QA1`). |
| **A2** | **Financial Precision & Evaluation Order**: Intermediate proration ratios and percentage rates use `NUMERIC(19,8)` (`BigDecimal` scale `8`–`12`), rounding `HALF_UP` to `NUMERIC(19,4)` at persisted line level; invoice subtotal equals the exact sum of rounded line amounts; evaluation order is `Raw Usage -> Prorated Free Allowance -> Charge Model -> Minimum Commitment True-Up -> Subtotal -> Tax -> Credit Wallet Drawdown -> Net Due`. | `shared`, `catalog`, `billing`, `payments`, [`DATA_MODEL.md`](./architecture/DATA_MODEL.md) | Verified by unit tests covering penny-rounding boundaries, proration, and tax/credit invariants (`QA2`). |
| **A3** | **Single Currency Per Subscription & Invoice**: Each customer account bills in one configured ISO-4217 currency (`USD` default) matching the subscribed `plan_version` currency; FX currency conversion is out of scope. | `subscriptions`, `catalog`, `billing`, `payments` | Enforced via service-layer currency match validation (`409 Conflict` on mismatch). |
| **A4** | **UTC Event & Cutoff Semantics**: All `event_timestamp`, `ingested_at`, subscription segment boundaries, `72-hour` late windows, and dunning schedules operate in UTC (`TIMESTAMPTZ`) via an injected `java.time.Clock`. | All modules, [`DATA_MODEL.md`](./architecture/DATA_MODEL.md) | Verified by deterministic clock-advancing unit and Testcontainers tests (`QA5`). |
| **A5** | **Java 25 Primary with Java 21 Source Compatibility**: Maven targets Java 25 by default while providing a `-Pjava21` fallback profile and keeping source syntax compatible with JDK 21 LTS. | `pom.xml` (planned), [ADR-001](./architecture/ADRs/ADR-001-modular-monolith-spring-modulith.md) | Verified during build baseline setup and GitHub Actions CI (`C11`). |

---

## 6. Deliverable Success Criteria

1. Complete, portable, contradiction-free architecture and traceability documentation across `FR1`–`FR10`, `QA1`–`QA5`, and `C1`–`C12`.
2. Zero Spring Modulith boundary violations or module cycles (`ApplicationModules.verify()`).
3. Clean Flyway schema validation on Testcontainers, Local Docker Compose PostgreSQL, and Shared Supabase PostgreSQL (`ddl-auto=validate`).
4. Full automated test passage covering all 5 aggregations, 6 charge models, 72h late-event handling, proration, credits/credit notes, 1-minute live estimates, dunning retries, HMAC webhooks, and plan immutability/simulation.
5. `k6` load benchmarks passing `QA1`/`QA2` latency, throughput, and idempotency targets on local Docker Compose PostgreSQL, and green GitHub Actions CI.
