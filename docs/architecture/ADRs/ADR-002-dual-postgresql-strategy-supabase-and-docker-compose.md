# ADR-002: Dual PostgreSQL Persistence Strategy (Supabase & Docker Compose) with Spring Data JPA and Flyway

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-002` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR1`–`FR10`, `QA1`, `QA2`, `QA3`, `QA5`, `C5`, `C6`, `C7`, `C9`, `C11` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow requires PostgreSQL-specific capabilities—`NUMERIC(19,4)`/`NUMERIC(19,8)` exact decimals, `TIMESTAMPTZ` UTC cutoffs, `JSONB` GIN indexes for `FR2` dimensional metric filters, partial unique indexes for idempotency (`FR1`, `FR8`), immutability triggers for `PUBLISHED` plan versions (`FR10`) and `FINALIZED` invoices (`FR5`), `event_publication` outbox atomicity (`C8`), and `FOR UPDATE SKIP LOCKED` webhook/dunning polling (`FR8`, `FR9`).

The project mandates **Supabase PostgreSQL** as the shared collaborative database and **Local PostgreSQL via Docker Compose** for local execution (`C5`, `C7`).

---

## 2. Decision Drivers

- **100% PostgreSQL Dialect Fidelity (`QA2`, `C5`, `C9`)**: Identical SQL behavior across local development, Testcontainers CI, and shared Supabase PostgreSQL.
- **Quota & Latency Isolation (`QA1`, `C10`)**: Protect shared free-tier Supabase connection limits from concurrent developer wipes and `100`-VU `k6` load tests.
- **Single DDL Authority (`C5`)**: Eliminate manual schema drift using forward-only Flyway migrations (`ddl-auto=validate`).
- **Zero Committed Credentials (`C6`)**: Externalize all Supabase connection strings and passwords via environment variables.

---

## 3. Considered Options

### Option A: Dual Spring Profiles (`local` Docker Compose PostgreSQL + `supabase` Shared Supabase PostgreSQL) Governed by Flyway + Testcontainers for Automated Tests
- **Pros**: Full PostgreSQL feature parity (`JSONB` GIN, `SKIP LOCKED`, partial unique indexes, triggers); offline development and local `k6` benchmarking; safe HikariCP pool sizing per environment.
- **Cons**: Requires Docker locally and Session-Mode (`5432`) endpoint awareness for Flyway on Supabase.

### Option B: Shared Supabase PostgreSQL Only
- **Pros**: Single host configuration.
- **Cons**: Breaks offline coding; risks credential exposure in CI; `k6` runs exhaust free-tier connections and measure WAN latency.

### Option C: H2 In-Memory Locally + Supabase PostgreSQL Remotely
- **Pros**: No Docker needed for tests.
- **Cons**: Incompatible with PostgreSQL `JSONB` `jsonb_path_ops` GIN indexes, partial unique indexes, `SKIP LOCKED` semantics, and PL/pgSQL immutability triggers.

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Dual-Profile PostgreSQL (`local` via Docker Compose + `supabase` via Shared Supabase) with Spring Data JPA, Flyway, and Testcontainers**.

1. **`local` Profile**: Connects to `compose.yaml` PostgreSQL 17 (`localhost:5432`, HikariCP `maximum-pool-size: 20`). Mandatory target for daily coding and **all `k6` load tests** (`C10`).
2. **`supabase` Profile**: Reads `SUPABASE_DB_URL`, `SUPABASE_DB_USERNAME`, and `SUPABASE_DB_PASSWORD` from environment variables (`C6`), caps HikariCP at `maximum-pool-size: 6`, and connects via Direct / Supavisor **Session Mode** (`5432`) so Flyway `pg_advisory_lock` succeeds.
3. **Automated Tests**: Use Testcontainers (`postgres:17-alpine` with `@ServiceConnection`), applying all Flyway migrations from `V1` with `spring.jpa.hibernate.ddl-auto=validate` and `spring.batch.jdbc.initialize-schema=never`.

---

## 5. Consequences

### Positive Consequences
- Zero code or SQL edits needed when toggling between `local` and `supabase` (`SC-QA3-2`).
- Deterministic CI execution without cloud secrets (`SC-QA5-1`).

### Negative Consequences and Trade-Offs
- Requires Docker daemon for local database and `mvn verify`.

### Risks and Mitigations
- **Risk**: Using Supabase Transaction Pooler (`6543`) breaks Flyway session advisory locks.
  - **Mitigation**: Enforce port `5432` Session Mode in `.env.example` and `application-supabase.yml`.

---

## 6. Compliance and Verification

- Testcontainers runs full Flyway migration chain + Hibernate `validate` on every CI build (`C5`, `C9`, `C11`).
- Secret audit verifies `.env` exclusion and zero credentials in tracked files (`C6`).

---

## 7. Related Documents

- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [ADR-008: Multi-Layer Verification](./ADR-008-verification-strategy-testcontainers-modulith-k6-ci.md)
