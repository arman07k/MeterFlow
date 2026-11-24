# MeterFlow Project Memory (`MEMORY.md`)

> **Governance Rule**: This document records **only confirmed project context, verified repository state, and locked architectural decisions** using portable repository-relative references. Never write speculative claims or secrets here.

---

## 1. Confirmed Project Identity & Repository State

- **Project Name**: MeterFlow (Usage-Based Metering, Subscription Rating, Invoicing, Dunning, and Webhook Platform)
- **Remote Repository**: `https://github.com/arman07k/MeterFlow.git` (`origin` configured cleanly without embedded credentials)
- **Active Branch**: `main`
- **Repository Root**: `./` (portable across developer machines)
- **Verified Repository Baseline (`2026-11-25`)**:
  - **Documentation & Architecture Foundation**: `25` Markdown files audited across all `16` mandatory deliverable categories, including `9` accepted ADRs (`ADR-001` through `ADR-009`).
  - **Build, Framework & Bootstrap Foundation (`Completed` — Verified & Ready for Initial Git Baseline)**:
    - Exact verified host & Maven Central toolchain: **Eclipse Temurin JDK `25.0.5.1+2-LTS`** (installed on system `JAVA_HOME`/`PATH`; `-Pjava21` fallback verified), **Maven `3.9.9`** (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` `3.3.4` `only-script`), **Docker Desktop `29.9.3`** & **Docker Compose `v5.7.1`**, **Spring Boot `4.0.5`**, **Spring Framework `7.0.6`**, **Spring Modulith `2.0.4`**, **Spring Batch `6.0.3`**, **Spring Security `7.0.4`**, **Spring Data JPA `4.0.4`**, **Hibernate ORM `7.2.7.Final`**, **Flyway `11.14.1`**, **PostgreSQL `17.12`** (`postgres:17-alpine`) & JDBC `42.7.10`, **Caffeine `3.2.3`**, **SpringDoc OpenAPI `3.0.0`**, **Tomcat `11.0.20`**, **Thymeleaf `3.1.3.RELEASE`**, **JUnit Jupiter `6.0.3`**, **Mockito `5.20.0`**, **Testcontainers `2.0.4`**.
    - Verified full Maven verification (`.\mvnw.cmd -B clean verify`, `BUILD SUCCESS` in `30.985 s`): **Surefire `15/15` passed** (`SecurityFoundationTests` `7/7`, `ModularityTests` `1/1`, `SharedFoundationUnitTests` `7/7`) and **Failsafe `2/2` passed** (`MeterFlowContextAndFlywayIT` on Testcontainers `postgres:17-alpine` verifying Flyway `V1`, `event_publication`, all `6` Spring Batch `BATCH_*` tables, Hibernate `ddl-auto=validate`, `CaffeineCacheManager`, `JobRepository`, `JobOperator`, `OutboxRetryScheduler.resubmitIncompleteEvents()`, disabled scheduling in `test`, and `OpenAPI`).
    - Verified full Docker Compose containerized runtime (`docker compose config -q`, `docker compose --profile full config -q`, `docker compose --profile full up --build -d` using `SERVER_PORT=18080` due to Windows Hyper-V/WinNAT TCP exclusion `8047–8146`): both `meterflow-postgres` (`postgres:17-alpine`, `41.89 MiB / 512 MiB`, `8.18%`, `Restarts=0`) and `meterflow-app` (`eclipse-temurin:25-jre-alpine`, non-root `USER meterflow`, `-XX:MaxRAMPercentage=50.0 -XX:+UseG1GC`, `276.8 MiB / 512 MiB`, `54.05%`, `Restarts=0`) booted `(healthy)` with combined RSS `318.69 MiB` (`< 4%` of 8 GB RAM), Flyway `V1` validated (`success = t`), Hibernate `ddl-auto=validate` passed, and `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`, and `/actuator/info` all returned `HTTP 200` (`UP`).
    - Verified bean & scheduling architecture: `OutboxRetryScheduler` is registered as an unconditional `@Component` (no premature `@ConditionalOnBean` before `EventPublicationAutoConfiguration`), while `SchedulingConfiguration` (`@EnableScheduling`, `SchedulingConfigurer`, `@ConditionalOnProperty(meterflow.scheduling.enabled)`) exclusively governs periodic execution (`true` in `local`/`supabase`, `false` in `test`) with `spring.modulith.moments.enabled=false`.
    - Domain business logic (`FR1`–`FR10`): **Not Started** (`0` domain features implemented).

---

## 2. Confirmed Functional & Quality Scope

- **Functional Requirements (`FR1`–`FR10`)** — Individually traced in [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md):
  - `FR1`: Usage event ingestion (single & batch up to `1,000`), `(customer_id, idempotency_key)` + `SHA-256` `payload_hash` (`200` duplicate / `409` conflict), and `72-hour` late-event handling.
  - `FR2`: Billable metrics supporting all `5` aggregation types (`COUNT`, `SUM`, `MAX`, `UNIQUE_COUNT`, `LATEST`) and configurable `JSONB` filters.
  - `FR3`: Pricing plans supporting all `6` mandatory charge types (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, **`PER_SEAT`**), free allowances, and minimum commitments via an Open/Closed Strategy + Composite `RatingEngine`.
  - `FR4`: Subscriptions, free/discounted trials, mid-cycle plan and `seat_quantity` changes, `NUMERIC(19,8)` time-proportional proration segments, and state transitions.
  - `FR5`: Two-step `DRAFT` and immutable `FINALIZED` invoices, deterministic tax, and full line-level provenance traceability.
  - `FR6`: Prepaid/promotional credit grants, immutable credit notes, and cumulative-delta post-finalization late adjustments.
  - `FR7`: Live current-period cost estimates (`0.70s` typical / `15.65s` worst-case freshness via sync `bucket_hour` rollups + `15s` Caffeine `liveEstimates`), customer spend budgets, and single-fire `usage.threshold_reached` alerts.
  - `FR8`: Deterministic simulated payment collection, exact three-attempt retry schedule (**Day 1 `+24h`, Day 3 `+72h`, Day 7 `+168h`**), separate suspension transition timer, customer notifications at every step, and recovery at any applicable point.
  - `FR9`: Outbound webhooks (`invoice.finalised`, `payment.failed`, `usage.threshold_reached`) with dual-key `HMAC-SHA256` signing & rotation, PostgreSQL `SKIP LOCKED` exponential backoff, persistent attempt history, and RBAC-scoped manual replay.
  - `FR10`: Immutable `PUBLISHED` `plan_versions` and `@Transactional(readOnly = true)` dry-run invoice simulation.
- **Quality Attributes (`QA1`–`QA5`) & Common Requirements (`C1`–`C12`)**: Retained with exact measurable acceptance targets in [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md), [`docs/architecture/QUALITY_ATTRIBUTE_PLAN.md`](./docs/architecture/QUALITY_ATTRIBUTE_PLAN.md), and [`docs/architecture/UTILITY_TREE.md`](./docs/architecture/UTILITY_TREE.md).

---

## 3. Confirmed Technology & Architecture Decisions

| Area | Locked Decision | Governing ADR |
| :--- | :--- | :--- |
| Runtime & Build | **Java 25** (primary), **Java 21 LTS** (`-Pjava21` fallback), **Maven**, **Spring Boot 4.x** | [ADR-001](./docs/architecture/ADRs/ADR-001-modular-monolith-spring-modulith.md) |
| Modularity | **Spring Modulith 2.x** (`10` acyclic modules: `shared`, `identity`, `catalog`, `subscriptions`, `metering`, `billing`, `payments`, `webhooks`, `notifications`, `portal`) | [ADR-001](./docs/architecture/ADRs/ADR-001-modular-monolith-spring-modulith.md) |
| Databases & Schema | **Supabase PostgreSQL** (`supabase` profile, Session Mode `5432`, HikariCP `6`) + **Local PostgreSQL via Docker Compose** (`local` profile, HikariCP `20`) + **Spring Data JPA** + **Flyway** (`ddl-auto=validate`) | [ADR-002](./docs/architecture/ADRs/ADR-002-dual-postgresql-strategy-supabase-and-docker-compose.md) |
| Caching | **Caffeine** (`planVersions`, `metricDefinitions`, `activeSubscriptionLookup`, `liveEstimates` `15s` TTL, `dashboardSummaries`) with after-commit eviction | [ADR-003](./docs/architecture/ADRs/ADR-003-in-memory-caching-with-caffeine.md) |
| Internal & External Queues | **PostgreSQL transactional outbox** (`event_publication`) + PostgreSQL `FOR UPDATE SKIP LOCKED` webhook/dunning retry queues | [ADR-004](./docs/architecture/ADRs/ADR-004-postgresql-transactional-outbox-messaging.md), [ADR-009](./docs/architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) |
| Batch Invoicing | **Spring Batch 6** (`generateDraftInvoicesJob` + `finalizeInvoicesJob`, `chunk = 100`) | [ADR-005](./docs/architecture/ADRs/ADR-005-batch-billing-and-aggregation-with-spring-batch.md) |
| UI & Security | **Spring MVC + Thymeleaf** + **Spring Security 7** (`BCrypt`, RBAC, CSRF, tenant scoping) | [ADR-006](./docs/architecture/ADRs/ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md) |
| Payments & Dunning | Internal deterministic **Simulated Payment** service + exact Day 1/3/7 retries & separate suspension timer | [ADR-007](./docs/architecture/ADRs/ADR-007-simulated-payment-processing-model.md) |
| Testing & CI | **JUnit 5**, **Mockito**, injectable `Clock`, **Testcontainers**, **k6**, **Docker Compose**, **GitHub Actions** | [ADR-008](./docs/architecture/ADRs/ADR-008-verification-strategy-testcontainers-modulith-k6-ci.md) |

---

## 4. Confirmed Prohibitions

- Zero **Redis**, zero **Kafka**, zero **paid cloud services or paid API keys**, zero **real payment gateways**, zero **React/Next.js frontends**, zero **unnecessary microservices or Kubernetes**, zero **duplicate business logic**, zero **machine-specific local-URI links** in repo docs, zero **sequential development-tracking numbers or release-gate labels**, and zero **committed secrets**.

---

## 5. Confirmed Open Operational Checks

1. **Supabase Connection Provisioning**: Supply `SUPABASE_DB_URL` (Session Mode port `5432`), `SUPABASE_DB_USERNAME`, and `SUPABASE_DB_PASSWORD` via untracked local environment variables when verifying the `supabase` profile (not connected during local foundation verification).
2. **SpringDoc Production Toggle**: `springdoc.swagger-ui.enabled` remains enabled by default for operator/developer verification (protected behind `ADMIN`/`OPERATOR` RBAC); set `springdoc.swagger-ui.enabled=false` when deploying to non-dev environments if Swagger UI exposure is not desired.
