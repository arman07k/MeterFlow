# ADR-010: Financial Precision, Currency Support, and Rounding Policy

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-010` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR3`, `FR4`, `FR5`, `FR6`, `FR7`, `FR8`, `FR10`, `QA2`, `C1`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow performs multi-step usage rating, second-level mid-cycle proration, tiered pricing, tax calculation, credit drawdown, credit notes, late-usage cumulative deltas, and payment settlement across **Bangladeshi Taka (`BDT`)** and **United States Dollars (`USD`)**.

Previous architecture records specified PostgreSQL storage scales (`NUMERIC(19,8)` for intermediate proration/rates and `NUMERIC(19,4)` for persisted invoice lines) and `RoundingMode.HALF_UP`, but did not formally separate:

1. **Supported currency boundaries** (`BDT`, `USD`) and rejection of unsupported currencies or implicit cross-currency arithmetic.
2. **Unrounded intermediate arithmetic** vs. explicit intermediate (`scale = 8`), persisted (`scale = 4`), and final payable minor-unit settlement (`scale = 2`: **paisa** for `BDT`, **cents** for `USD`) rounding.
3. **Numerical equality semantics** for `Money` (preventing scale-sensitive `BigDecimal.equals` bugs where `10.00 BDT != 10.0000 BDT`).
4. **Safe JSON serialization** preventing floating-point loss or scientific notation (`1E+3`).

---

## 2. Decision Drivers

- **Zero Rounding Drift (`QA2`)**: Eliminate floating-point inaccuracy (`float`/`double` strictly prohibited) and premature intermediate rounding across chained proration and tier calculations.
- **Last-Paisa & Last-Cent Settlement Accuracy**: Final payable amounts in `BDT` must be exact to the last paisa (`0.01 BDT`), and final payable amounts in `USD` must be exact to the last cent (`0.01 USD`).
- **Explicit Currency Isolation**: No automatic FX conversion; cross-currency addition, subtraction, or comparison must fail immediately with `CurrencyMismatchException`.
- **Single Canonical Implementation (`QA3`)**: Every module (`catalog`, `subscriptions`, `metering`, `billing`, `payments`, `portal`) must reuse one immutable `com.meterflow.shared.Money` and `com.meterflow.shared.SupportedCurrency`.

---

## 3. Considered Options

### Option A: Immutable `Money` with Exact Intermediate Arithmetic and Explicit Three-Tier Rounding Policy
- Preserve exact `BigDecimal` precision during construction, `add`, `subtract`, `negate`, `abs`, and `multiply(BigDecimal)` without implicit rounding.
- Provide explicit, named rounding operations aligned to PostgreSQL storage and settlement boundaries:
  - `roundToIntermediate()` -> `scale = 8`, `RoundingMode.HALF_UP` (`NUMERIC(19,8)` proration factors and unit rates).
  - `roundToPersisted()` -> `scale = 4`, `RoundingMode.HALF_UP` (`NUMERIC(19,4)` persisted invoice/credit/ledger lines).
  - `roundToPayable()` -> `currency.minorUnitScale()` (`2` decimals: paisa for `BDT`, cents for `USD`), `RoundingMode.HALF_UP`.
  - `divide(divisor, scale, roundingMode)` -> requires explicit scale and rounding mode; rejects zero divisor.
- Enforce scale-insensitive numerical equality (`compareTo == 0`) with consistent `hashCode()` normalization, and serialize JSON amounts as plain decimal strings (`toPlainString()`).

### Option B: Eager Rounding on Every Operation (`scale = 4` in Constructor)
- **Rejected**: Forces implicit rounding on every intermediate multiplication or proration step, causing cumulative drift across multi-tier and mid-cycle proration formulas and coupling `Money` permanently to a single scale.

### Option C: Minor-Unit `long` Cents/Paisa Storage Only
- **Rejected**: Cannot represent sub-paisa/sub-cent unit rates (`$0.0001` per API call) or `NUMERIC(19,8)` proration factors required by `FR3` and `FR4`.

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Immutable Exact `BigDecimal` `Money` with Explicit Three-Tier Rounding Policy (`HALF_UP`)**.

| Calculation Tier | Scale | Rounding Mode | Usage in MeterFlow |
| :--- | :---: | :---: | :--- |
| **Unrounded Intermediate Arithmetic** | Exact (`BigDecimal`) | None (explicit only) | In-memory multiplication (`qty * unitPrice * prorationFactor`), additions, and subtractions inside `RatingEngine`. |
| **Intermediate Persisted Precision** | `8` (`NUMERIC(19,8)`) | `HALF_UP` | Proration factors (`segment_seconds / period_seconds`), effective unit prices, and tax/percentage intermediates (`roundToIntermediate()`). |
| **Persisted Line / Ledger Precision** | `4` (`NUMERIC(19,4)`) | `HALF_UP` | Persisted `invoice_lines`, `credit_note_lines`, `late_usage_adjustments`, and `ledger_entries` (`roundToPersisted()`). |
| **Final Settlement / Payable Precision** | `2` (`BDT` paisa, `USD` cents) | `HALF_UP` | Final customer payable invoice total and simulated payment capture amount (`roundToPayable()`). |

### Mandatory Invariants
1. **Supported Currencies**: Exclusively `SupportedCurrency.BDT` (`minorUnitScale = 2`) and `SupportedCurrency.USD` (`minorUnitScale = 2`). Unsupported ISO codes fail with `InvalidCurrencyException`.
2. **Zero `float`/`double`**: `Money` exposes zero `float` or `double` constructors or factory methods.
3. **Numerical Equality**: `Money.equals` compares `currency` and `amount.compareTo(other.amount) == 0`; `hashCode()` normalizes zero (`-0.0 -> 0`) and strips trailing zeros so numerically equal values always produce identical hash codes.
4. **JSON Representation**: Serializes as `{"amount":"<plain-decimal-string>","currency":"BDT|USD"}` via `BigDecimal.toPlainString()` and deserializes strictly via `SupportedCurrency` and `BigDecimal` validation.

---

## 5. Consequences

### Positive Consequences
- Eliminates premature intermediate rounding drift while guaranteeing exact paisa (`BDT`) and cent (`USD`) payable totals.
- Eliminates scale-sensitive `BigDecimal.equals` defects in tests, collections, and reconciliation checks.
- Keeps `com.meterflow.shared` free of JPA or web infrastructure dependencies.

### Negative Consequences and Trade-Offs
- Callers persisting values or finalizing payment amounts must explicitly invoke `roundToPersisted()` or `roundToPayable()` rather than relying on implicit constructor rounding.

### Risks and Mitigations
- **Risk**: Developer forgets to round before settlement or persistence.
  - **Mitigation**: Database columns enforce `NUMERIC(19,4)`/`NUMERIC(19,8)`, and billing/payment service tests assert exact scale and `0.00` reconciliation drift.

---

## 6. Compliance and Verification

- `MoneyUnitTests` verifies `BDT` and `USD` arithmetic, currency mismatch rejection, zero implicit intermediate rounding, `roundToIntermediate()`, `roundToPersisted()`, `roundToPayable()` (last-paisa / last-cent), scale-insensitive `equals`/`hashCode`, negative/null/zero-division rejection, and Jackson 3 JSON round-trip serialization.
- `ModularityTests` confirms all modules depend only on the canonical `com.meterflow.shared.Money`.

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
- [`ADR-001`](./ADR-001-modular-monolith-spring-modulith.md)
