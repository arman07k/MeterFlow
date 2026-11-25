# ADR-012: Subscription State Transitions, Billing-Period Semantics, and Proration Policy

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-012` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR4`, `FR3`, `FR10`, `QA2`, `QA3`, `QA4`, `C1`, `C5`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

Implementing `FR4` (subscription management, trials, mid-cycle upgrades/downgrades, scheduled period-end changes, pause/resume, cancellation, and proration) inside `com.meterflow.subscriptions` requires explicit, deterministic financial and concurrency rules covering:

1. Permitted subscription states and valid transitions (including trial expiry, pause/resume period handling, and immediate vs. end-of-period cancellation).
2. Calendar billing-period generation across variable-length months, leap years, and IANA timezones with UTC persistence.
3. Exact `BigDecimal` / `Money` proration arithmetic without duplicating `catalog`'s `RatingEngineFacade` or erroneously prorating usage-based charges as recurring fixed fees.
4. Database-backed concurrency safety, idempotency, and restart-safe scheduled execution without external brokers or distributed schedulers.

---

## 2. Decision Drivers

- **Financial Exactness (`QA2`, `ADR-010`)**: Zero `float`/`double`; exact UTC second-level ratios; no premature intermediate rounding; explicit `NUMERIC(19,8)` proration factors and `NUMERIC(19,4)` `HALF_UP` persisted credit/debit/net amounts.
- **Single Source of Pricing Truth (`QA3`, `ADR-001`)**: `subscriptions` obtains comparable recurring plan amounts strictly through `catalog.api.RatingEngineFacade.rateRecurringCharges(...)`—never reimplementing charge math or accessing `catalog.internal`.
- **Safe Recurring vs. Usage Separation**: Only fixed recurring charges (`FLAT_FEE` and contracted `PER_SEAT` with `metricId == null`) participate in recurring proration; usage-based charges (`PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, metered `PER_SEAT`) are governed by immutable plan-assignment segments `[effective_from, effective_to)` for future metering and billing.
- **Database-Enforced Reliability (`C5`, `C7`)**: Rely on PostgreSQL partial unique indexes, optimistic/pessimistic row locking, and repeatable database scans rather than in-memory locks or external queues.

---

## 3. Considered Options

### Option A: Exact-Seconds UTC Proration (`EXACT_SECONDS_V1`), Calendar-Clamped Zoned Periods, and Recurring-Only Quote Contract
- **Single Open Subscription Policy**: At most one non-terminal subscription (`TRIALING`, `ACTIVE`, `PAUSED`, `PAST_DUE`) per customer, enforced by partial unique index `uq_subscriptions_one_open_per_customer`.
- **Trial Expiry Policy**: Configurable per subscription (`EXPIRE_ON_END` default — transitions expired trials to `EXPIRED` without silently billing; optional `AUTO_CONVERT_TO_PAID` explicitly converts to `ACTIVE`).
- **Pause/Resume Policy**: Pausing records `paused_at` and remaining paid period seconds (`paused_remaining_seconds`); resuming shifts the active billing period forward (`newPeriodStart = resumedAt`, `newPeriodEnd = resumedAt + remainingSeconds`) so customers never lose paid time or accrue recurring fees while paused.
- **Billing Periods**: Generated via `ZonedDateTime` in the subscription's IANA `billingTimezone` (`MONTHLY` via `plusMonths(1)`, `ANNUAL` via `plusYears(1)`), clamping variable-length months (`Jan 31 -> Feb 28/29`) and leap years (`Feb 29 -> Feb 28`), and stored strictly as UTC `TIMESTAMPTZ`.
- **Proration Formula (`EXACT_SECONDS_V1`)**:
  - `totalSeconds = Duration.between(periodStart, periodEnd).getSeconds()`
  - `remainingSeconds = Duration.between(effectiveAt, periodEnd).getSeconds()`
  - `prorationFactor = remainingSeconds / totalSeconds` stored at `NUMERIC(19,8)` `HALF_UP`
  - `creditAmount = (oldRecurringAmount * remainingSeconds) / totalSeconds` -> rounded once to `NUMERIC(19,4)` `HALF_UP`
  - `debitAmount = (newRecurringAmount * remainingSeconds) / totalSeconds` -> rounded once to `NUMERIC(19,4)` `HALF_UP`
  - `netProrationAmount = debitAmount - creditAmount` (positive = upgrade charge, negative = downgrade credit)
  - Persisted immutably in `subscription_proration_records` alongside `non_proratable_usage_charges JSONB`.

### Option B: Day-Count 30/360 Approximation & Flat-Fee-Only Assumption
- **Rejected**: Introduces off-by-one boundary drift across 28/29/30/31-day months and DST transitions, and fails on plans combining `FLAT_FEE`, contracted `PER_SEAT`, and usage-based charges.

---

## 4. Decision Outcome

**Chosen Option**: **Option A**.

### State-Transition Matrix
| From State | Permitted Target States | Trigger |
| :--- | :--- | :--- |
| `TRIALING` | `ACTIVE` | Explicit conversion or due `AUTO_CONVERT_TO_PAID` |
| `TRIALING` | `EXPIRED` | Due `EXPIRE_ON_END` maintenance processing |
| `TRIALING` | `CANCELLED` | Immediate trial cancellation |
| `ACTIVE` | `PAUSED` | Authorized pause request |
| `ACTIVE` | `CANCELLED` | Immediate cancellation or due `cancel_at_period_end` processing |
| `ACTIVE` | `PAST_DUE` | Reserved for future `payments` dunning integration |
| `PAUSED` | `ACTIVE` | Authorized resume (shifts remaining paid period forward) |
| `PAUSED` | `CANCELLED` | Immediate cancellation from paused state |
| `PAST_DUE` | `ACTIVE`, `CANCELLED` | Reserved for future `payments` recovery / termination |
| `CANCELLED`, `EXPIRED` | *(none — terminal)* | Permanent historical records |

### Scheduled Processing Reliability
- `SubscriptionMaintenanceProcessor` executes repeatable, idempotent scans over PostgreSQL indexes for:
  1. Due trials (`status = 'TRIALING' AND trial_end_at <= now`)
  2. Due scheduled plan changes (`status = 'PENDING' AND effective_at <= now`)
  3. Due end-of-period cancellations (`status = 'ACTIVE' AND cancel_at_period_end = TRUE AND current_period_end <= now`)
- Each due record is processed inside its own transaction with a pessimistic row lock (`SELECT ... FOR UPDATE`), ensuring restart safety, retry safety, and zero duplicate executions.

---

## 5. Consequences

### Positive Consequences
- Exact second-level proration with zero premature rounding and full audit trail (`subscription_proration_records`, `subscription_plan_assignments`, `subscription_state_transitions`).
- Zero duplicated pricing code; clear separation between recurring proration and usage-based segment rating.
- Safe under concurrent requests and process restarts without external brokers.

### Negative Consequences and Trade-Offs
- Single-open-subscription constraint per customer requires cancelling or expiring an open subscription before creating a new one (intentional guard against accidental double-billing).
- Usage-based mid-cycle charges wait for the upcoming `metering` and `billing` modules to aggregate usage per `subscription_plan_assignments` window.

### Risks and Mitigations
- **Risk**: Concurrent plan changes or state mutations race on the same subscription.
  - **Mitigation**: `@Version` optimistic locking on `subscriptions` + pessimistic row locking (`FOR UPDATE`) on mutation transactions + unique partial idempotency indexes.

---

## 6. Compliance and Verification

- `BillingPeriodAndProrationUnitTests`: Verifies month-end clamping, leap years, UTC & DST timezones, `0%`/`100%`/mid-cycle proration, upgrades/downgrades, recurring vs. usage exclusion, trial validation, and state-transition rules.
- `SubscriptionModuleIT`: Verifies Flyway `V4`, Hibernate `ddl-auto=validate`, PostgreSQL constraints, customer isolation, REST endpoints, proration persistence, trial conversion/expiry, scheduled plan changes, repeatable maintenance runs, concurrent mutations, and idempotency on PostgreSQL 17 Testcontainers.
- `ModularityTests`: Verifies zero cycles and strict `catalog :: api` / `identity :: api` / `subscriptions :: api` boundaries.

---

## 7. Related Documents

- [`ADR-001`](./ADR-001-modular-monolith-spring-modulith.md)
- [`ADR-010`](./ADR-010-financial-precision-currency-and-rounding-policy.md)
- [`ADR-011`](./ADR-011-pricing-tiers-packages-allowances-and-commitments.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
