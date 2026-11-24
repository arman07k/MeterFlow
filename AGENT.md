# MeterFlow Agent Engineering Handbook (`AGENT.md`)

## 1. Purpose

This handbook defines the mandatory engineering rules, architectural boundaries, and task workflow for every AI coding agent and human contributor working in **MeterFlow**. Following this document guarantees continuity across contributors and machines without context drift, duplicated business logic, or violated constraints.

---

## 2. Mandatory Context Loading Order

At the start of **every** task, read these files in exact order before inspecting or modifying source files:

1. [`AGENT.md`](./AGENT.md) — Mandatory workflow, guardrails, and verification gates.
2. [`MEMORY.md`](./MEMORY.md) — Confirmed project facts, locked technology decisions, and verified repository state.
3. [`PLAN.md`](./PLAN.md) — Current deliverable statuses (`Not Started` | `In Progress` | `Blocked` | `Completed`) and next actionable deliverable.
4. Task-relevant specifications:
   - [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md) — Individual mappings and measurable acceptance criteria for `FR1`–`FR10`, `QA1`–`QA5`, and `C1`–`C12`.
   - [`docs/architecture/MODULE_BOUNDARIES.md`](./docs/architecture/MODULE_BOUNDARIES.md) — Ten-module Spring Modulith DAG, `@NamedInterface("api")` contracts, and shared `RatingEngine` rules.
   - [`docs/architecture/DATA_MODEL.md`](./docs/architecture/DATA_MODEL.md) — PostgreSQL schema ownership, `NUMERIC(19,8)`/`NUMERIC(19,4)` rules, immutability triggers, and Flyway conventions.
   - [`docs/architecture/IMPLEMENTATION_CHECKLIST.md`](./docs/architecture/IMPLEMENTATION_CHECKLIST.md) — Granular deliverable verification items.
   - Governing records in [`docs/architecture/ADRs/`](./docs/architecture/ADRs/README.md) (`ADR-001` through `ADR-009`).

---

## 3. Non-Negotiable Technology & Repository Guardrails

### 3.1 Confirmed Technology Stack

| Capability | Mandatory Technology | Governing ADR |
| :--- | :--- | :--- |
| Runtime & Build | Java 25 (primary) / Java 21 LTS (`-Pjava21` fallback), Apache Maven | [ADR-001](./docs/architecture/ADRs/ADR-001-modular-monolith-spring-modulith.md) |
| Framework & Modularity | Spring Boot 4.x (`jakarta.*`), Spring Modulith 2.x (`10` acyclic modules) | [ADR-001](./docs/architecture/ADRs/ADR-001-modular-monolith-spring-modulith.md) |
| Databases | Shared **Supabase PostgreSQL** (`supabase`) + **Local PostgreSQL via Docker Compose** (`local`) | [ADR-002](./docs/architecture/ADRs/ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md) |
| Persistence & DDL | Spring Data JPA (`ddl-auto=validate`) + Flyway forward-only SQL migrations | [ADR-002](./docs/architecture/ADRs/ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md) |
| Caching | Bounded **Caffeine** caches (`planVersions`, `metricDefinitions`, `activeSubscriptionLookup`, `liveEstimates`, `dashboardSummaries`) | [ADR-003](./docs/architecture/ADRs/ADR-003-in-memory-caching-with-caffeine.md) |
| Internal Messaging | PostgreSQL-backed transactional outbox (`event_publication`, `completion-mode=UPDATE`, `republish-outstanding-events-on-restart=true`, `externalization.enabled=false`, `moments.enabled=false`; `OutboxRetryScheduler` + `SchedulingConfiguration`) | [ADR-004](./docs/architecture/ADRs/ADR-004-postgresql-transactional-outbox-messaging.md) |
| Batch Invoicing | Spring Batch 6 (`generateDraftInvoicesJob`, `finalizeInvoicesJob`, `chunk = 100`) | [ADR-005](./docs/architecture/ADRs/ADR-005-batch-billing-and-aggregation-with-spring-batch.md) |
| Web UI & Security | Spring MVC + Thymeleaf + Dual Spring Security 7 `SecurityFilterChain`s + SpringDoc OpenAPI `3.0.0` (`/v3/api-docs`) | [ADR-006](./docs/architecture/ADRs/ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md) |
| Payments & Dunning | Deterministic internal `SimulatedPaymentService`, exact Day 1/3/7 (`[24, 72, 168]`) retries & separate suspension timer | [ADR-007](./docs/architecture/ADRs/ADR-007-simulated-payment-processing-model.md) |
| Verification & CI | JUnit 5, Mockito, injectable `Clock`, Testcontainers PostgreSQL, `k6`, GitHub Actions | [ADR-008](./docs/architecture/ADRs/ADR-008-verification-strategy-testcontainers-modulith-k6-ci.md) |
| Outbound Webhooks | PostgreSQL `SKIP LOCKED` queue (`invoice.finalised`, `payment.failed`, `usage.threshold_reached`), dual-key `HMAC-SHA256`, backoff & replay | [ADR-009](./docs/architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) |

### 3.2 Strict Prohibitions

Never introduce any of the following without explicit stakeholder approval and a superseding ADR:

- **No Redis** (or external distributed caches).
- **No Kafka** (or RabbitMQ / external message brokers).
- **No paid cloud services or paid API keys**.
- **No real payment gateway integrations** (no Stripe, PayPal, Braintree, etc.).
- **No React or Next.js frontend** (or separate SPA build).
- **No unnecessary microservices or Kubernetes manifests**.
- **No duplicate business logic** — `catalog.api.RatingEngineFacade` must remain the sole implementation of charge/allowance/minimum-commitment math across batch invoices (`FR5`), late adjustments (`FR6`), live estimates (`FR7`), and simulations (`FR10`).
- **No redundant documentation or machine-specific links** — Use **portable relative Markdown links** (`./...`, `../...`) exclusively inside repository files; never commit local-filesystem URI links or workstation-specific directory paths.
- **No sequential development-tracking numbers or release-gate labels** — Organize all documentation, filenames, code comments, and commit messages strictly around **feature names, functional requirements (`FR1`–`FR10`), quality attributes (`QA1`–`QA5`), common requirements (`C1`–`C12`), architecture responsibilities, and deliverables**.
- **No secrets in Git** — Never store or log PATs, database passwords, or webhook signing secrets in repository files or `.git/config`.

---

## 4. Mandatory Task Execution Workflow

```mermaid
flowchart LR
    A["1. Load Context & Verify Scope"] --> B["2. Check Module DAG & ADRs"]
    B --> C["3. Implement & Run Automated Checks"]
    C --> D["4. Synchronize Living Engineering Records"]
```

### 4.1 Pre-Task Checks
1. Inspect `git status` and `git diff` to preserve all existing workspace changes.
2. Identify the target deliverable in [`PLAN.md`](./PLAN.md) and exact requirement IDs in [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md).
3. Verify module ownership in [`docs/architecture/MODULE_BOUNDARIES.md`](./docs/architecture/MODULE_BOUNDARIES.md) (`shared`, `identity`, `catalog`, `subscriptions`, `metering`, `billing`, `payments`, `webhooks`, `notifications`, `portal`).

### 4.2 Implementation Invariants (Future Coding Tasks)
1. **Financial Exactness (`QA2`)**: Use `BigDecimal` (`NUMERIC(19,8)` intermediate proration/percentage math -> `NUMERIC(19,4)` `HALF_UP` line rounding); prohibit `double`/`float`; preserve immutability of `PUBLISHED` `plan_versions` (`FR10`) and `FINALIZED` invoices (`FR5`).
2. **Cache Freshness (`FR7`, `QA1`)**: Store only immutable `record` DTOs in Caffeine; trigger mutable-source cache evictions via after-commit callbacks; keep `liveEstimates` TTL at `15s` backed by synchronous hourly `usage_rollups` (`<= 15.65s` worst-case freshness `<< 60s`), and never read Caffeine `liveEstimates` for authoritative invoicing (`FR5`, `FR6`).
3. **Database & Load Safety (`C5`, `C10`)**: Express schema changes exclusively via forward-only Flyway migrations; connect Flyway to Supabase via Session Mode port `5432`; run destructive tests on Testcontainers and **all `k6` load tests strictly against Local Docker Compose PostgreSQL**.

### 4.3 Verification Gates
- `./mvnw test` passes (unit matrix + `ApplicationModules.verify()`).
- `./mvnw verify` passes (Testcontainers PostgreSQL migrations, Spring Batch, outbox, dunning, webhook HMAC/backoff, and security tests).
- `docker compose config -q` and `docker compose --profile full up --build -d` boot healthy `meterflow-postgres` and `meterflow-app` containers within `512m` memory caps (`SERVER_PORT` override supported if host `8080` is reserved).
- All internal Markdown links are relative and resolve cleanly across platforms.

### 4.4 Mandatory Post-Task Record Synchronization
Before concluding any task, update all affected records truthfully (never fabricating completed features or test results):
- [`PLAN.md`](./PLAN.md), [`MEMORY.md`](./MEMORY.md), [`CHANGELOG.md`](./CHANGELOG.md), [`docs/WORK_LOG.md`](./docs/WORK_LOG.md), [`docs/AI_USAGE_LOG.md`](./docs/AI_USAGE_LOG.md), [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md), [`docs/architecture/IMPLEMENTATION_CHECKLIST.md`](./docs/architecture/IMPLEMENTATION_CHECKLIST.md), and [`docs/architecture/ADRs/`](./docs/architecture/ADRs/README.md) if architectural decisions evolve.
