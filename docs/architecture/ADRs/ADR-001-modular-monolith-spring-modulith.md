# ADR-001: Modular Monolith Architecture with Spring Modulith and Shared Rating Engine on Spring Boot 4 / Java 25

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-001` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR1`–`FR10`, `QA1`, `QA3`, `QA5`, `C1`, `C7`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow spans ten distinct capabilities across SaaS usage-based billing: authentication/RBAC (`identity`), billable metrics and multi-model pricing (`catalog`), subscriptions and mid-cycle proration (`subscriptions`), idempotent `72h` event ingestion, aggregations, `<= 1m` live estimates, and budgets (`metering`), draft/final batch invoicing, tax, credits, credit notes, late adjustments, and invoice simulation (`billing`), simulated payment retries and dunning (`payments`), outbound `HMAC-SHA256` webhooks (`webhooks`), alerts/audit trails (`notifications`), and web/API adapters (`portal`).

We must enforce strict domain encapsulation and **zero duplicate pricing logic** (`C1`, `QA3`) while prohibiting microservices, Kubernetes, and unnecessary infrastructure (`C7`), targeting **Java 25 LTS** on **Spring Boot 4.x** with **Java 21 LTS** fallback buildability.

---

## 2. Decision Drivers

- **Enforced Modularity (`C1`, `QA3`)**: Catch cross-module entity imports and cyclic dependencies automatically during `mvn test`.
- **Single Source of Rating Truth (`FR3`, `FR5`–`FR7`, `FR10`)**: Guarantee `0.0000` parity across batch invoices, late-event delta re-rating, `<= 1m` live estimates, and dry-run simulations without duplicating charge formulas.
- **Minimal Infrastructure (`C7`, `C11`)**: Package as one Maven-built Spring Boot 4.x JAR backed solely by PostgreSQL.
- **Runtime Performance & Portability (`QA1`, `QA5`)**: Enable Virtual Threads (`spring.threads.virtual.enabled=true`) on Java 25 while preserving `-Pjava21` compiler fallback compatibility.

---

## 3. Considered Options

### Option A: Ten-Module Spring Modulith Monolith with Single Stateless `RatingEngine` in `catalog`
- **Pros**: Automated `ApplicationModules.verify()` enforcement; zero microservice network hops; single ACID PostgreSQL outbox; `100%` reuse of `RatingEngineFacade` across `billing` and `metering`.
- **Cons**: Requires strict package visibility (`internal` vs. `@NamedInterface("api")`) and Java 21-compatible syntax for fallback builds.

### Option B: Distributed Microservices on Kubernetes
- **Pros**: Independent process scaling.
- **Cons**: **Explicitly prohibited** (`No unnecessary microservices`, `No Kubernetes`, `C7`); forces distributed sagas across invoicing, credits, and dunning.

### Option C: Horizontal Layered Monolith (`controller` / `service` / `repository`)
- **Pros**: Simple package setup.
- **Cons**: Fails `C1`/`QA3`; encourages direct foreign-table mutations, `billing <-> payments` cycles, and duplicated charge logic.

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Ten-Module Spring Modulith Monolith on Spring Boot 4.x, Maven, and Java 25 (with `-Pjava21` Fallback)**.

1. Partition `com.meterflow` into `shared` (`OPEN`), `identity`, `catalog`, `subscriptions`, `metering`, `billing`, `payments`, `webhooks`, `notifications`, and `portal` ([`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)).
2. Centralize all `6` mandatory charge models (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, **`PER_SEAT`**), free allowances, and minimum commitments inside `catalog.api.RatingEngineFacade` using an Open/Closed **Strategy (`ChargeCalculationStrategy` + `ChargeStrategyRegistry`) + Composite (`PlanRatingComposite`)** design so any future charge type (e.g., an optional `PERCENTAGE` strategy) plugs in without modifying existing charge classes.
3. Target `jakarta.*` (Jakarta EE 11 / Spring Framework 7) exclusively; keep source constructs compatible with JDK 21 so `-Pjava21` compiles cleanly if JDK 25 is absent.

---

## 5. Consequences

### Positive Consequences
- Zero cyclic dependencies and zero duplicated rating math across four major features (`SC-QA3-1`).
- Fast single-JAR startup and Testcontainers execution (`SC-QA5-1`).

### Negative Consequences and Trade-Offs
- Modules scale vertically/together inside one JVM until any future extraction is warranted.

### Risks and Mitigations
- **Risk**: Spring Boot 4 modularized starters require explicit dependency declarations in `pom.xml`.
  - **Mitigation**: Validate full context startup with Testcontainers during the initial build baseline deliverable.

---

## 6. Compliance and Verification

- `ModularityTests` executes `ApplicationModules.of(MeterFlowApplication.class).verify()` on every `mvn test` run (`0` violations allowed).
- Code audit verifies `RatingEngine` exists exclusively inside `com.meterflow.catalog`.

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
- [`PATTERN_JUSTIFICATION.md`](../PATTERN_JUSTIFICATION.md)
