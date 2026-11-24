# ADR-007: Deterministic Internal Simulated Payment Collection, Day 1/3/7 Retry Schedule, and Separate Suspension Dunning Workflow

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-007` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR8`, `FR9`, `QA2`, `QA5`, `C7`, `C8`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

`FR8` requires automated and manual invoice payment collection with **exactly three payment retry attempts on Day 1, Day 3, and Day 7**, clear transitions across initial payment failure, `PAST_DUE`, retries 1–3, `FINAL_WARNING`, subscription `SUSPENDED`, and successful-payment recovery at any applicable point, with customer notifications at every step—all **without real payment gateway integrations or paid external services** (`C7`).

Combining retry offsets and suspension timers into a single array (e.g., `[24, 72, 168, 336]`) is an architectural defect because it mistakenly creates a fourth payment attempt. Payment retry timers and account state-transition timers must be modeled separately.

---

## 2. Decision Drivers

- **Exact Instructor Retry Schedule (`FR8`)**: Strictly **three** payment retries at **Day 1 (`+24h`)**, **Day 3 (`+72h`)**, and **Day 7 (`+168h`)** after initial failure `T0`—never a fourth payment retry.
- **Separate State-Transition Timers (`FR8`)**: Decouple the post-retry suspension transition (`suspension_offset_hours` / `suspension_at`) from payment execution timers.
- **Zero External Payment Gateways (`C7`)**: Deterministic internal `SimulatedPaymentService` with `uq_payment_idempotency` and `uq_captured_payment_per_invoice`.
- **Notification & Recovery Completeness (`FR8`, `FR9`)**: Emit customer notifications at every transition, `payment.failed` webhooks on every payment failure, and support immediate recovery upon settlement.

---

## 3. Considered Options

### Option A: Deterministic `SimulatedPaymentService` + Separate Retry Schedule (`[24, 72, 168]`) & Suspension Timer (`suspension_offset_hours`) in PostgreSQL
- **Pros**: Enforces `CHECK (jsonb_array_length(retry_offsets_hours) = 3)` and `retries_completed BETWEEN 0 AND 3`; zero risk of an unintended 4th retry; `100%` clock-steppable in Testcontainers (`QA5`); zero external gateway dependencies (`C7`).
- **Cons**: Requires two distinct polling predicates (`idx_dunning_cases_due_retry` vs. `idx_dunning_cases_due_suspend`).

### Option B: Combined Four-Element Offset Array `[24, 72, 168, 336]`
- **Pros**: Single timer column.
- **Cons**: **Violates `FR8`** by executing a fourth payment retry at Day 14 instead of exactly three retries on Days 1, 3, and 7.

### Option C: External Payment Gateway Sandbox (Stripe Test Mode)
- **Pros**: Vendor-hosted retries.
- **Cons**: **Explicitly prohibited** (`No real payment gateway integrations`, `C7`).

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Internal Deterministic `SimulatedPaymentService` with Exact Day 1 / Day 3 / Day 7 Retries and Separate Suspension Transition Timer**.

### Exact Dunning Timeline & Customer Notifications

| Transition / Event | Timing Relative to Initial Failure `T0` | Payment Attempt? | Invoice / Subscription / Case State | Customer Notification & Webhook |
| :--- | :--- | :---: | :--- | :--- |
| **Initial Payment Failure** | `T0` (at invoice finalization) | Yes (Initial) | Invoice -> `PAST_DUE`; Sub -> `PAST_DUE`; Case -> `RETRY_SCHEDULED` (`next_retry_at = T0 + 24h`) | Customer failure notice + **`payment.failed`** webhook |
| **Retry Attempt 1** | **Day 1 (`T0 + 24h`)** | **Yes (Retry 1/3)** | On fail: `retries_completed = 1`, `next_retry_at = T0 + 72h` | Customer Retry 1 failure notice + **`payment.failed`** webhook |
| **Retry Attempt 2** | **Day 3 (`T0 + 72h`)** | **Yes (Retry 2/3)** | On fail: `retries_completed = 2`, `next_retry_at = T0 + 168h` | Customer Retry 2 failure notice + **`payment.failed`** webhook |
| **Retry Attempt 3 (Final)** | **Day 7 (`T0 + 168h`)** | **Yes (Retry 3/3)** | On fail: `retries_completed = 3`, `next_retry_at = NULL`, Case -> **`FINAL_WARNING`**, **`suspension_at = T0 + suspension_offset_hours`** (e.g., `336h` / Day 14 grace or `168h` immediate) | Customer final-warning notice + **`payment.failed`** webhook |
| **Suspension Transition** | **`suspension_at`** (separate timer) | **No (`0` 4th retry)** | Case -> **`SUSPENDED`**; `subscriptions` listener transitions Sub `PAST_DUE` -> **`SUSPENDED`** | Customer suspension notice + optional status webhook |
| **Successful-Payment Recovery** | **Any applicable point** (`RETRY_SCHEDULED`, `FINAL_WARNING`, or `SUSPENDED`) | Yes (Retry or Manual) | `CAPTURED` payment + balanced `ledger_entries`; Case -> **`RECOVERED`** (clears timers); Invoice -> **`PAID`**; Sub -> **`ACTIVE`** | Customer payment-recovery & reactivation notice |

*(If `SIMULATE_HARD_DECLINE` occurs at `T0`, automated retries are bypassed: invoice/subscription transition to `PAST_DUE`, `FINAL_WARNING` notice + `payment.failed` webhook fire immediately, and `suspension_at` arms for customer payment-method recovery.)*

---

## 5. Consequences

### Positive Consequences
- Exact compliance with the instructor's Day 1, Day 3, Day 7 retry specification and zero ambiguity between payment execution and account suspension (`FR8`).

### Negative Consequences and Trade-Offs
- Requires two lightweight scheduled pollers (or two queries inside `DunningWorker`): one for due retries (`status = 'RETRY_SCHEDULED'`) and one for due suspensions (`status = 'FINAL_WARNING'`).

### Risks and Mitigations
- **Risk**: Race between a manual customer payment and Retry 1/2/3 executing simultaneously.
  - **Mitigation**: `uq_captured_payment_per_invoice` ensures PostgreSQL permits at most one `CAPTURED` payment per invoice; losing transaction aborts cleanly without double ledger posting.

---

## 6. Compliance and Verification

- Zero external payment SDKs in `pom.xml` (`C7`).
- Clock-stepped Testcontainers test advances `Clock` across `T0`, `+24h`, `+72h`, `+168h`, and `suspension_at`, asserting **exactly `3`** rows in `dunning_attempts`, `0` 4th payment attempt at suspension, customer notification records at every transition, and clean recovery from `RETRY_SCHEDULED`, `FINAL_WARNING`, and `SUSPENDED` (`FR8`, `C9`).

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [ADR-004: PostgreSQL Transactional Outbox](./ADR-004-postgresql-transactional-outbox-messaging.md)
- [ADR-009: Outbound HMAC Webhooks](./ADR-009-outbound-hmac-webhooks-backoff-and-replay.md)
