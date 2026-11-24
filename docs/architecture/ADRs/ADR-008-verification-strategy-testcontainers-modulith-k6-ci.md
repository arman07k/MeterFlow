# ADR-008: Multi-Layer Verification with JUnit 5, Injectable Clock, Testcontainers, k6, and GitHub Actions

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-008` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `QA1`–`QA5`, `C1`, `C5`, `C9`, `C10`, `C11`, `C12` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow contains intricate financial and temporal rules (`72h` late cutoffs, `5` aggregations with `JSONB` filters, `6` charge models, `NUMERIC(19,8)` mid-cycle proration, `FINALIZED` invoice & `PUBLISHED` plan immutability, cumulative-delta late adjustments, `<= 1m` live estimates, multi-day dunning retries, and `HMAC-SHA256` exponential-backoff webhooks) that cannot be verified with H2 mocks or wall-clock `Thread.sleep` delays.

---

## 2. Decision Drivers

- **True PostgreSQL Fidelity (`QA2`, `C5`, `C9`)**: Validate real PostgreSQL `JSONB` GIN indexes, partial unique indexes, `SKIP LOCKED`, triggers, and Flyway scripts via Testcontainers.
- **Instant Deterministic Time Testing (`QA5`, `SC-QA5-1`)**: Step across `72` hours, `30` days, and `14`-day dunning schedules instantaneously using an injected `java.time.Clock`.
- **Enforced Modularity (`QA3`, `C1`)**: Verify the `10`-module DAG automatically on every build.
- **Quantitative Load Proof (`QA1`, `C10`)**: Assert `p95`/`p99` latencies, `1m` freshness, and `50`-VU idempotency with `k6` against Local Docker Compose PostgreSQL.

---

## 3. Considered Options

### Option A: Four-Layer Pyramid (JUnit 5 + Mockito + `MutableClock`, Spring Modulith Verification, Singleton Testcontainers PostgreSQL, and Local `k6` Suite) Automated in GitHub Actions
- **Pros**: Fast deterministic CI (`< 3 min`) with zero external secrets; `100%` PostgreSQL dialect parity; quantitative `k6` SLI gates (`C9`–`C11`).
- **Cons**: Requires Docker daemon in local and CI environments (standard on GitHub Actions Ubuntu runners).

### Option B: Unit Tests + H2 In-Memory Database
- **Pros**: No Docker dependency.
- **Cons**: Fails on PostgreSQL `JSONB` operators, partial unique indexes, `SKIP LOCKED`, and immutability triggers.

### Option C: Integration & Load Testing Against Shared Supabase PostgreSQL
- **Pros**: Tests hosted DB directly.
- **Cons**: Violates credential isolation (`C6`) and exhausts free-tier Supabase connection limits (`ADR-002`).

---

## 4. Decision Outcome

**Chosen Option**: **Option A — JUnit 5, Mockito, Injectable `Clock`, Spring Modulith `ApplicationModules.verify()`, Singleton Testcontainers PostgreSQL, `k6`, and GitHub Actions**.

1. **Unit & Rating Matrix (`./mvnw test`)**: Parameterized tests covering all `5` aggregations, `5` filter operators, all `6` mandatory charge models (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`), an OCP custom charge strategy extension test, free allowances, minimum commitments, `NUMERIC(19,8)` proration math, and dual-key `HMAC-SHA256` signing, plus `ModularityTests`.
2. **Testcontainers Integration Suite (`./mvnw verify`)**: Shared `@ServiceConnection` `postgres:17-alpine` container verifying Flyway migrations, `idempotency_key` `200`/`409` & `50`-thread concurrency, `1,000`-event partial-acceptance batch, `< 60s` live freshness (`FR7`), two-step Spring Batch jobs, credit notes & cumulative-delta late adjustments, `Clock`-stepped Day 1/3/7 retries + separate suspension timer (`FR8`), MockMvc RBAC/CSRF/IDOR security (including `DRAFT` simulation & webhook replay authorization), and local HTTP receiver webhook backoff, secret rotation, and replay (`FR9`).
3. **`k6` Load Suite (`perf/k6/*.js`)**: Executed strictly against Local Docker Compose PostgreSQL (`C10`).
4. **GitHub Actions (`.github/workflows/ci.yml`)**: Runs `./mvnw -B verify` on every push and PR (`C11`).

---

## 5. Consequences

### Positive Consequences
- Zero flaky time-dependent sleeps and zero cloud secret requirements in CI (`SC-QA5-1`).

### Negative Consequences and Trade-Offs
- Contributors need Docker running locally for `./mvnw verify` and `k6` runs.

### Risks and Mitigations
- **Risk**: Multiple `@SpringBootTest` configurations spawning extra PostgreSQL containers.
  - **Mitigation**: Centralize a single `AbstractPostgresIntegrationTest` base configuration so Spring Test context caching reuses one container across the suite.

---

## 6. Compliance and Verification

- Green `.github/workflows/ci.yml` execution on `main` (`C11`).
- Honest logging of actual test and `k6` outputs in [`docs/WORK_LOG.md`](../../WORK_LOG.md) (`C12`).

---

## 7. Related Documents

- [`QUALITY_ATTRIBUTE_PLAN.md`](../QUALITY_ATTRIBUTE_PLAN.md)
- [`UTILITY_TREE.md`](../UTILITY_TREE.md)
- [`IMPLEMENTATION_CHECKLIST.md`](../IMPLEMENTATION_CHECKLIST.md)
