# Changelog

All notable architecture, specification, and engineering changes to **MeterFlow** are documented in this file.

Entries follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and are organized strictly by **features, functional requirements (`FR1`–`FR10`), quality attributes (`QA1`–`QA5`), common requirements (`C1`–`C12`), architecture modules, and deliverables**.

---

## [Unreleased]

### Fixed

- **Docker Container JVM Memory Tuning & Full Compose Smoke Test (`C5`, `C11`, `QA1`, `QA5`)**:
  - Replaced `-XX:MaxRAMPercentage=75.0 -XX:+UseZGC` with `-XX:MaxRAMPercentage=50.0 -XX:+UseG1GC` in `Dockerfile` after `docker stats --no-stream` revealed `meterflow-app` consuming `502.8 MiB / 512 MiB` (`98.20%`) under ZGC; rebuilt container dropped to `276.8 MiB / 512 MiB` (`54.05%`) alongside `meterflow-postgres` at `41.89 MiB / 512 MiB` (`8.18%`, combined `318.69 MiB` / `< 4%` of 8 GB RAM).
  - Verified full containerized stack (`docker compose --profile full up --build -d`) with `Restarts=0`, Flyway `V1` validated, Hibernate `ddl-auto=validate` passed, and `/actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness`, and `/actuator/info` returning `HTTP 200` (`UP`), establishing the initial Git repository baseline commit.
- **Outbox Retry Scheduler Registration, Deterministic Profile Scheduling & Full Maven Verification (`C1`, `C5`, `C8`, `C9`, `QA5`)**:
  - Removed premature `@ConditionalOnBean(IncompleteEventPublications.class)` and direct `@Scheduled` from `OutboxRetryScheduler` (which had been evaluated before Spring Modulith's `EventPublicationAutoConfiguration`, causing `NoSuchBeanDefinitionException` in `MeterFlowContextAndFlywayIT`).
  - Converted `SchedulingConfiguration` to implement `SchedulingConfigurer`, registering the `15s` fixed-delay outbox retry task exclusively when `meterflow.scheduling.enabled=true` (`local`/`supabase`) while keeping `OutboxRetryScheduler` registered and directly callable without background scheduling in `test`.
  - Set `spring.modulith.moments.enabled=false` and explicit `meterflow.scheduling.enabled=true` in `application.yml` so Spring Modulith's `MomentsAutoConfiguration` does not implicitly activate `@EnableScheduling` during tests.
  - Switched `MeterFlowContextAndFlywayIT` to a Spring-managed `@TestConfiguration` `@Bean @ServiceConnection PostgreSQLContainer`, eliminating container-before-Hikari shutdown warnings and Failsafe fork exit timeouts.
  - Verified full `.\mvnw.cmd -B clean verify` (`BUILD SUCCESS` in `30.985 s`: Surefire `15/15`, Failsafe `2/2` on Testcontainers `postgres:17-alpine`), `docker compose` local PostgreSQL `17.12` health, and live `local` profile Flyway `V1` + `/actuator/health/readiness` (`HTTP 200` `UP`).

### Changed

- **Independent Review Corrections & `FR9`/`FR10` Deep Audit (`FR1`, `FR3`, `FR7`, `FR8`, `FR9`, `FR10`, `QA1`–`QA5`)**:
  - **FR3 (`PER_SEAT` + Strategy/Composite OCP)**: Replaced `PERCENTAGE` in the core six charge models with mandatory **`PER_SEAT`** (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`), added `seat_quantity` to `subscriptions` and `subscription_segments`, and formalized `ChargeCalculationStrategy` + `PlanRatingComposite` so future charge types plug in without modifying existing calculation classes.
  - **FR8 (Exact Day 1 / Day 3 / Day 7 Retries & Separate Suspension Timer)**: Corrected `dunning_policies.retry_offsets_hours` to strictly `[24, 72, 168]` (3 retries: Day 1, Day 3, Day 7) and isolated `suspension_offset_hours` / `suspension_at` as a separate state-transition timer with zero 4th payment attempt, documenting notifications at every transition and recovery at any applicable point.
  - **FR7 (Proven One-Minute Freshness Budget)**: Replaced unverified `30s` cache + minute-rollup wording with synchronous in-transaction `bucket_hour` `usage_rollups` (`0s` outbox/aggregation lag) + after-commit eviction + `15s` Caffeine `liveEstimates` TTL ceiling (`0.70s` typical / `15.65s` proven worst-case freshness `<< 60s`), while prohibiting authoritative billing from reading Caffeine caches.
  - **FR1 (`idempotency_key` Identity & Payload Conflict Guard)**: Replaced ambiguous `transaction_id` column with explicit `(customer_id, idempotency_key)` + canonical `SHA-256` `payload_hash` (`200 DUPLICATE_IGNORED` on identical replay, `409 IDEMPOTENCY_KEY_CONFLICT` on mismatched payload, idempotency check executed before `72h` expiration check, `1,000`-event batch support).
  - **FR9 & FR10 (Webhooks & Simulation)**: Formalized mandatory **`invoice.finalised`**, **`payment.failed`**, and **`usage.threshold_reached`** webhook events, encrypted dual-key secret rotation (`active_signing_secret_enc` + `previous_signing_secret_enc` + `previous_secret_expires_at`), constant-time `<= 300s` receiver verification, RBAC-scoped manual replay with fresh timestamp signing, and `@Transactional(readOnly = true)` `FR10` invoice simulation RBAC.
- **Comprehensive Architecture & `FR1`–`FR10` Specification Alignment Audit (`FR1`–`FR10`, `QA1`–`QA5`, `C1`–`C12`)**:
  - Aligned [`docs/PROJECT_CHARTER.md`](./docs/PROJECT_CHARTER.md), [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md), [`docs/architecture/SYSTEM_DESIGN.md`](./docs/architecture/SYSTEM_DESIGN.md), [`docs/architecture/MODULE_BOUNDARIES.md`](./docs/architecture/MODULE_BOUNDARIES.md), [`docs/architecture/DATA_MODEL.md`](./docs/architecture/DATA_MODEL.md), [`docs/architecture/QUALITY_ATTRIBUTE_PLAN.md`](./docs/architecture/QUALITY_ATTRIBUTE_PLAN.md), [`docs/architecture/UTILITY_TREE.md`](./docs/architecture/UTILITY_TREE.md), [`docs/architecture/PATTERN_JUSTIFICATION.md`](./docs/architecture/PATTERN_JUSTIFICATION.md), [`docs/architecture/IMPLEMENTATION_CHECKLIST.md`](./docs/architecture/IMPLEMENTATION_CHECKLIST.md), and `ADR-001`–`ADR-009` across all `10` Spring Modulith modules and removed workstation-specific paths for `100%` portability (`C12`).

### Added

- **Infrastructure Quality Audit Hardening (`C1`, `C5`, `C7`, `C8`, `QA5`)**:
  - Replaced invalid JDBC-only `spring.modulith.events.jdbc-schema-initialization.enabled` key in `application.yml` with verified Spring Modulith `2.0.4` JPA event-registry properties (`completion-mode=UPDATE`, `republish-outstanding-events-on-restart=true`, `externalization.enabled=false`).
  - Hardened `compose.yaml` with TCP-verified PostgreSQL healthcheck (`pg_isready -h 127.0.0.1`), `512m` memory cap on `postgres`, and `/actuator/health/readiness` probe on `app`.
  - Removed unused `thymeleaf-extras-springsecurity6` from `pom.xml` and added `-XX:+EnableDynamicAgentLoading` to Surefire/Failsafe to eliminate JDK 25 Mockito agent warnings.
- **OpenAPI 3, Spring Batch 6 Executor, Outbox Sweeper & Observability Foundation (`C5`, `C6`, `C8`, `C9`, `QA4`, `QA5`)**:
  - Added `springdoc-openapi-starter-webmvc-ui:3.0.0` and `OpenApiConfiguration` (`MeterFlow API` + `basicAuth` scheme) with `/v3/api-docs/**` and `/swagger-ui/**` restricted to `ADMIN`/`OPERATOR`.
  - Added `BatchInfrastructureConfiguration` (`meterflowBatchTaskExecutor` virtual-thread executor bounded to `4` in `local` and `1` in `supabase`), `OutboxRetryScheduler` (`IncompleteEventPublications` `15s` sweeper), `GlobalApiExceptionHandler` (RFC 9457 `ProblemDetail` with `correlationId`), and Actuator liveness/readiness probes + `info.app` metadata.
  - Removed duplicated Hibernate/Flyway/Batch keys from `application-test.yml`, activated `@ActiveProfiles("test")` in `SecurityFoundationTests`, and expanded passing test coverage to `14/14` (`SecurityFoundationTests` `7/7`, `ModularityTests` `1/1`, `SharedFoundationUnitTests` `6/6`).
- **Build System, Local PostgreSQL, Flyway V1, Modulith Skeleton, Security & CI Baseline (`C1`, `C5`–`C9`, `C11`, `QA2`–`QA5`)**:
  - Added Maven `pom.xml` (`Spring Boot 4.0.5`, `Spring Modulith 2.0.4`, `Spring Batch 6.0.3`, `Spring Security 7.0.4`, `Flyway 11.14.1`, `Caffeine 3.2.3`, `Testcontainers 2.0.4`, Java `25` primary + `-Pjava21` fallback) and Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`).
  - Added `.gitignore`, `.env.example`, `compose.yaml` (`postgres:17-alpine` + optional `full` app profile), `Dockerfile` (Temurin 25 builder/runtime), and `.github/workflows/ci.yml`.
  - Added `application.yml`, `application-local.yml` (HikariCP `20`), `application-supabase.yml` (Session Mode `5432`, HikariCP `6`), `application-test.yml`, and `V1__framework_outbox_and_batch_schema.sql` (`event_publication` + Spring Batch 6 `BATCH_*` tables and `BATCH_JOB_INSTANCE_SEQ`).
  - Added `MeterFlowApplication`, all `10` Spring Modulith module boundary packages (`package-info.java` + `@NamedInterface("api")`), `shared` foundation beans (`Money`, `ClockConfiguration`, `CacheNames`, `CaffeineCacheConfiguration`, `SchedulingConfiguration`), dual Spring Security 7 chains (`SecurityConfiguration`), `CorrelationIdFilter`, and minimal Thymeleaf bootstrap views (`index.html`, `login.html`).
  - Added and verified `ModularityTests` (`1/1`), `SharedFoundationUnitTests` (`3/3`), and `SecurityFoundationTests` (`6/6`), plus Testcontainers integration suite `MeterFlowContextAndFlywayIT` (compiles cleanly; runtime execution pending Docker installation).
- **Outbound Webhook Architecture Record (`FR9`, `C2`, `C6`–`C8`)**:
  - Added [`docs/architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md`](./docs/architecture/ADRs/ADR-009-outbound-hmac-webhooks-backoff-and-replay.md) specifying `HMAC-SHA256` payload signing (`X-MeterFlow-Signature`), SSRF validation, PostgreSQL `FOR UPDATE SKIP LOCKED` exponential backoff retries, immutable `webhook_delivery_attempts` history, and event replay.
- **Initial Project Governance & Architecture Foundation (`C1`–`C4`, `C12`)**:
  - Added [`AGENT.md`](./AGENT.md), [`MEMORY.md`](./MEMORY.md), [`PLAN.md`](./PLAN.md), [`docs/WORK_LOG.md`](./docs/WORK_LOG.md), [`docs/AI_USAGE_LOG.md`](./docs/AI_USAGE_LOG.md), [`docs/PROJECT_CHARTER.md`](./docs/PROJECT_CHARTER.md), [`docs/REQUIREMENTS_TRACEABILITY.md`](./docs/REQUIREMENTS_TRACEABILITY.md), core architecture documents under `docs/architecture/`, and `ADR-001` through `ADR-008`.

---

## Entry Guidelines for Future Tasks

1. Record changes under `[Unreleased]` inside `Added`, `Changed`, `Fixed`, `Removed`, or `Security`.
2. Reference applicable Requirement IDs (`FR*`, `QA*`, `C*`) and ADR IDs (`ADR-*`).
3. Never use sequential development-tracking numbers, release-gate labels, or time-boxed cadence identifiers.
4. Only record implemented functionality when source code and passing tests exist in the repository.
