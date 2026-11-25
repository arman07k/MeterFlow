# ADR-011: Pricing Engine Tier, Package, Free Allowance, and Minimum Commitment Semantics

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-011` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR2`, `FR3`, `FR10`, `QA2`, `QA3`, `C1`, `C5`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

Implementing `FR2` (billable metric configurations), `FR3` (six mandatory charge types, free allowances, and minimum commitments), and `FR10` (immutable plan versions and read-only simulation) inside `com.meterflow.catalog` requires unambiguous mathematical and persistence rules for:

1. Tier bracket boundaries and differences between `GRADUATED_TIERS` and `VOLUME_TIERS`.
2. Partial-package billing semantics for `PACKAGE` charges (e.g., per `1,000` units).
3. Deterministic single-consumption free allowance allocation when multiple charges share a metric.
4. Non-duplicative charge-level vs. plan-level minimum commitment true-ups.
5. Open/Closed extensibility allowing a new charge type without editing existing calculators or database check constraints.
6. Database-level immutability enforcement for `PUBLISHED` plan versions.

---

## 2. Decision Drivers

- **Zero Ambiguity & Determinism (`QA2`)**: Identical rating inputs against a historical plan version must always produce byte-for-byte identical monetary results and explainable breakdowns.
- **Alignment with `ADR-010`**: All arithmetic uses `com.meterflow.shared.Money` (`BDT`/`USD`, exact `BigDecimal` intermediates, explicit `scale=8` / `scale=4` / `scale=2` `HALF_UP` rounding).
- **Open/Closed Extensibility (`QA3`, `ADR-001`)**: Adding a future charge strategy must require only one new `ChargeCalculationStrategy` bean registration without modifying existing strategies or `PlanRatingComposite`.
- **Financial Immutability (`FR10`)**: Published pricing definitions must be protected both in application logic (`409 Conflict`) and at the PostgreSQL trigger boundary.

---

## 3. Considered Options

### Option A: Formalized Half-Open Continuous Tiers, Ceiling Package Units, Single-Pass Allowance Ledger, and Strategy Registry SPI
- **Tiers**: Continuous non-overlapping brackets `(lowerBound, upperBound]` starting at `lowerBound = 0`, where `billableQty == 0` yields `0` charge.
  - `GRADUATED_TIERS`: Marginal units in each crossed bracket multiplied by that bracket's `unitPrice` plus optional `flatFee`.
  - `VOLUME_TIERS`: Entire `billableQty` multiplied by the single bracket containing `billableQty` plus optional `flatFee`.
- **Package Partial Units**: **Ceiling (`RoundingMode.CEILING`)** — any positive usage fraction rounds up to the next whole package (`packages = CEIL(billableQty / packageSize)` when `billableQty > 0`; `0` when `billableQty == 0`). Example: `1,001` units at `1,000/pkg` bills `2` packages.
- **Free Allowances**: Single-pass remaining-allowance map per rating execution consumed in deterministic charge `(displayOrder ASC, id ASC)` order; charge-scoped allowances apply before shared metric-scoped allowances.
- **Minimum Commitments**: Charge-level commitments apply once to their target charge; plan-level commitment applies once across the sum of all rated charges (`trueUp = max(0, planMin - sum(chargeFinals))`).
- **Strategy Registry**: String-keyed `ChargeStrategyRegistry` resolving Spring-injected `ChargeCalculationStrategy` beans (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`, plus any future registered strategy).

### Option B: Floor/Fractional Packages and Central `switch` Enum
- **Rejected**: Floor rounding under-bills partial packages (`1–999` units billed as `0`); fractional package pricing duplicates `PER_UNIT`; a central `switch` violates the Open/Closed Principle (`QA3`).

---

## 4. Decision Outcome

**Chosen Option**: **Option A**.

1. **Metric Semantics (`FR2`)**:
   - Five mandatory aggregation types: `SUM`, `COUNT`, `MAXIMUM`, `UNIQUE_COUNT`, `LATEST_VALUE`.
   - Five mandatory filter operators: `EQUALS`, `NOT_EQUALS`, `IN`, `NOT_IN`, `EXISTS`.
   - `LATEST_VALUE` tie-breaking order: `(eventTimestamp DESC, ingestedAt DESC, eventId DESC)`.
   - Missing, `null`, or non-numeric values for numeric aggregations (`SUM`, `MAXIMUM`, `LATEST_VALUE`) fail explicitly rather than defaulting to `0`.
2. **Tier Boundaries**:
   - First tier `lowerBound == 0`; `lowerBound >= 0`; `lowerBound < upperBound` for bounded tiers; `tier[i].lowerBound == tier[i-1].upperBound`; only the final tier may be unbounded (`upperBound == null`).
   - If a bounded final tier is configured and `billableQty > maxTier.upperBound`, rating fails explicitly with `InvalidRatingContextException` (preventing silent mispricing).
3. **Package Partial-Unit Rule**:
   - `billableQty == 0` -> `0` packages (`0` charge).
   - `billableQty > 0` -> `packages = billableQty.divide(packageSize, 0, RoundingMode.CEILING)`; `amount = packagePrice * packages`.
4. **Per-Seat Rule (`PER_SEAT`)**:
   - Uses contracted `seatQuantity` (`>= 0`) from `RatingRequest` when `metricId == null`, or aggregated metric usage when linked to a metric; deducts applicable free allowance once, then multiplies billable seats by `unitPrice`.
5. **Database Immutability Triggers (`V3__catalog_schema.sql`)**:
   - PostgreSQL triggers reject `DELETE` on `PUBLISHED`/`ARCHIVED` `plan_versions`, reject any modification of pricing fields on `PUBLISHED` versions (allowing only `PUBLISHED -> ARCHIVED`), and reject `INSERT`/`UPDATE`/`DELETE` on `plan_charges`, `charge_tiers`, `free_allowances`, and `minimum_commitments` once their parent `plan_version` is `PUBLISHED` or `ARCHIVED`.
6. **Cache Authority Separation (`ADR-003`)**:
   - Caffeine `planVersions` and `metricDefinitions` accelerate read-only catalog queries (with after-commit eviction on mutations), while `RatingEngineFacade` loads directly from PostgreSQL inside a read-only transaction so cached data is never financial authority.

---

## 5. Consequences

### Positive Consequences
- Guarantees deterministic, explainable rating across all six mandatory charge models and future OCP extensions.
- Prevents double allowance consumption, double minimum-commitment charging, off-by-one tier bugs, and accidental mutation of historical pricing.

### Negative Consequences and Trade-Offs
- Callers configuring bounded top tiers must explicitly configure an unbounded top tier if arbitrary usage growth is expected.

### Risks and Mitigations
- **Risk**: Concurrent draft/clone requests race on `version_number`.
  - **Mitigation**: Pessimistic row lock on parent `pricing_plans` (`FOR UPDATE`) plus `UNIQUE (plan_id, version_number)`.

---

## 6. Compliance and Verification

- `MetricDefinitionUnitTests`: Verifies all `5` aggregation types, `5` filter operators, missing/null/invalid numeric rejection, and `LATEST_VALUE` tie-breaking.
- `PricingEngineUnitTests`: Verifies all `6` mandatory charge types, graduated vs. volume tier boundaries, package ceiling partial units, per-seat quantities, single-consumption allowances, plan/charge minimum commitments, `ADR-010` rounding, and OCP extensibility with a test-only 7th charge strategy.
- `CatalogModuleIT`: Verifies Flyway `V3`, PostgreSQL immutability triggers, concurrent version creation, customer isolation, REST authorization, and read-only simulation on PostgreSQL 17 Testcontainers.

---

## 7. Related Documents

- [`ADR-001`](./ADR-001-modular-monolith-spring-modulith.md)
- [`ADR-003`](./ADR-003-in-memory-caching-with-caffeine.md)
- [`ADR-010`](./ADR-010-financial-precision-currency-and-rounding-policy.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [`PATTERN_JUSTIFICATION.md`](../PATTERN_JUSTIFICATION.md)
