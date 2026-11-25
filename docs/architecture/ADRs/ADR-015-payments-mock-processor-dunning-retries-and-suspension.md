# ADR-015: Payments & Dunning Engine — Deterministic Mock Processor, Day 1/3/7 Retries, Separate Suspension Timer, and ACID Settlement

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-015` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR8`, `FR9`, `QA2`, `QA3`, `QA4`, `QA5`, `C1`, `C5`, `C6`, `C7`, `C8`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

`FR8` and [ADR-007](./ADR-007-simulated-payment-processing-model.md) require MeterFlow to collect payments against eligible finalized invoices using a deterministic internal mock payment engine (zero external payment gateways or paid services), enforce exact Day 1 (`+24h`), Day 3 (`+72h`), and Day 7 (`+168h`) payment retries, decouple post-exhaustion subscription suspension from payment retry attempts, reactivate eligible `PAST_DUE`/`SUSPENDED` subscriptions upon successful payment without ever restoring `CANCELLED`/`EXPIRED` subscriptions, and preserve strict financial atomicity and auditability across concurrent requests and application restarts.

---

## 2. Decision Drivers

- **Single Financial Source of Truth (`QA2`)**: `billing` (`invoices`) remains the sole authority for invoice balances (`amount_due`, `payable_amount_due`) and invoice status (`FINALIZED`, `PAST_DUE`, `PAID`), while `subscriptions` remains the sole authority for subscription state transitions (`ACTIVE`, `PAST_DUE`, `SUSPENDED`, `CANCELLED`, `EXPIRED`).
- **ACID Cross-Module Consistency Without Cycles (`C1`, `QA2`, `QA3`)**: Within the acyclic Spring Modulith DAG (`payments -> billing :: api`, `payments -> subscriptions :: api`, `payments -> identity :: api`, `payments -> shared`), synchronous facade calls inside the payment/dunning transaction ensure rollback atomicity while Spring Modulith outbox events notify future `FR9` webhooks and notifications asynchronously.
- **Database-Enforced Duplicate Settlement Immunity (`QA2`)**: Application checks alone cannot prevent concurrent duplicate captures; PostgreSQL must enforce `uq_captured_payment_per_invoice`, `uq_payment_tx_idempotency`, advisory/pessimistic locks, and append-only immutability triggers.
- **Restart-Safe, Multi-Instance Dunning Execution (`FR8`, `QA2`, `QA5`)**: Retry and suspension timers live in PostgreSQL (`dunning_cases`) and are claimed in bounded batches using `FOR UPDATE SKIP LOCKED` with injectable `java.time.Clock`.
- **Security Separation (`C6`, `QA4`)**: Mock payment outcome controls are strictly restricted to `ROLE_ADMIN` / `ROLE_OPERATOR` and test harnesses—never exposed to customer-scoped endpoints.

---

## 3. Considered Options

### Option A: Encapsulated `com.meterflow.payments` (`V7`), Synchronous ACID Settlement/Subscription Hooks, PostgreSQL `SKIP LOCKED` Dunning Worker & Outbox Events
- **Pros**: Guarantees atomic rollback across payment record, attempt history, dunning case, invoice settlement, and subscription reactivation; strictly acyclic Modulith DAG; PostgreSQL enforces single captured settlement per invoice and append-only immutability; 100% clock-steppable in Testcontainers.
- **Cons**: Requires minimal non-breaking public contract extensions on `billing.api` (`recordPaymentSettlement`, `markInvoicePastDue`) and `subscriptions.api` (`SUSPENDED`, `markPastDue`, `suspendForNonPayment`, `reactivateAfterPayment`).

### Option B: Eventual-Consistency-Only Invoice Settlement & Subscription Updates via Async Listeners
- **Pros**: Zero synchronous facade calls from `payments` to `billing`/`subscriptions`.
- **Cons**: Creates a transient window where a payment is `CAPTURED` while `invoices.status` is still `FINALIZED`/`PAST_DUE` or rolls back independently on downstream failure; fails strict atomic rollback invariants.

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Encapsulated `com.meterflow.payments` with Flyway `V7`, Deterministic Mock Processor, Synchronous ACID Upstream Contracts, PostgreSQL `SKIP LOCKED` Dunning Worker, and Outbox Events**.

### 4.1 Deterministic Mock Payment Processing
- `MockPaymentGateway` resolves outcomes strictly from internal deterministic inputs (`SIMULATE_SUCCESS`, `SIMULATE_SOFT_DECLINE`, `SIMULATE_HARD_DECLINE`, `SIMULATE_TIMEOUT`) with zero external network calls or gateway SDKs.
- `SIMULATE_SOFT_DECLINE` and `SIMULATE_TIMEOUT` are classified as `retryable = true` (entering the Day 1/3/7 schedule); `SIMULATE_HARD_DECLINE` is classified as `retryable = false` (bypassing automated retries into `FINAL_WARNING` with `suspension_at` armed).
- Default operator outcome is externalized via `meterflow.payments.mock.default-outcome` (`SIMULATE_SUCCESS`); customers cannot supply or override simulation outcomes.

### 4.2 Exact Day 1 / Day 3 / Day 7 Retry Policy & Separate Suspension Timer
- `dunning_policies` stores `retry_offsets_hours` constrained by `CHECK (jsonb_array_length(retry_offsets_hours) = 3)` (default `[24, 72, 168]`) and separate `suspension_offset_hours` (`CHECK (suspension_offset_hours >= 168)`, default `336`).
- On initial retryable failure at `T0`: invoice transitions `FINALIZED -> PAST_DUE`, eligible `ACTIVE` subscription transitions `ACTIVE -> PAST_DUE`, and `dunning_cases` enters `RETRY_SCHEDULED` with `retries_completed = 0`, `next_retry_at = T0 + 24h`.
- Retry 1 (`T0 + 24h`) failure -> `retries_completed = 1`, `next_retry_at = T0 + 72h`.
- Retry 2 (`T0 + 72h`) failure -> `retries_completed = 2`, `next_retry_at = T0 + 168h`.
- Retry 3 (`T0 + 168h`) failure -> `retries_completed = 3`, `next_retry_at = NULL`, status transitions to `FINAL_WARNING`, and `suspension_at = T0 + suspension_offset_hours`. No 4th payment attempt is ever scheduled or executed.
- At `suspension_at`: the suspension poller executes **zero** payment attempts, transitions `dunning_cases` to `SUSPENDED`, calls `subscriptionManagementFacade.suspendForNonPayment(...)` (ignoring terminal `CANCELLED`/`EXPIRED`), and publishes `SubscriptionSuspendedForNonPaymentEvent`.
- Successful payment at any point (`RETRY_SCHEDULED`, `FINAL_WARNING`, or `SUSPENDED`): settles `invoices` (`PAID`), transitions `dunning_cases` to `RECOVERED` (clearing `next_retry_at` and `suspension_at`), calls `subscriptionManagementFacade.reactivateAfterPayment(...)` (restoring only `PAST_DUE` or `SUSPENDED` to `ACTIVE`, never touching `CANCELLED`, `EXPIRED`, or manually `PAUSED`), and publishes `PaymentSucceededEvent` and `SubscriptionReactivatedAfterPaymentEvent`.

### 4.3 Database Persistence, Concurrency & Immutability (`V7`)
- `V7__payments_and_dunning_schema.sql` preserves `V1`–`V6` untouched, extends `subscriptions` check constraints and `uq_subscriptions_one_open_per_customer` to include `SUSPENDED`, and adds `payment_reference_seq`, `payment_transactions`, `payment_attempts`, `dunning_policies`, `dunning_cases`, and `dunning_events`.
- Concurrency protection combines transaction-scoped PostgreSQL advisory locks (`pg_advisory_xact_lock` on `invoiceId`), `PESSIMISTIC_WRITE` row locks on `invoices`/`subscriptions`/`dunning_cases`, `FOR UPDATE SKIP LOCKED` bounded worker claims (`batchSize = 50`, isolated per-case transactions), `uq_captured_payment_per_invoice`, and `uq_payment_tx_idempotency`.
- PostgreSQL `BEFORE UPDATE OR DELETE` triggers guard completed `payment_transactions`, append-only `payment_attempts`, and append-only `dunning_events` against mutation or deletion.

---

## 5. Consequences

### Positive Consequences
- Zero double settlement, zero overpayment, zero 4th payment retry, and atomic invoice/subscription/payment rollback under concurrent load or restart.
- All five domain events (`PaymentSucceededEvent`, `PaymentFailedEvent`, `PaymentRetryScheduledEvent`, `SubscriptionSuspendedForNonPaymentEvent`, `SubscriptionReactivatedAfterPaymentEvent`) persist transactionally in `event_publication` ready for `FR9`.

### Negative Consequences and Trade-Offs
- Dunning worker runs two bounded `SKIP LOCKED` passes (due retries and due suspensions) in isolated per-case transactions to avoid long-running database locks.

### Risks and Mitigations
- **Risk**: Race between a manual customer payment and an automated scheduled retry.
  - **Mitigation**: Both acquire `pg_advisory_xact_lock(invoiceId)` and lock `invoices`; `uq_captured_payment_per_invoice` guarantees at most one `CAPTURED` settlement per invoice.

---

## 6. Compliance and Verification

- `ModularityTests` verifies `payments` module encapsulation and acyclic dependencies (`C1`, `QA3`).
- `PaymentAndDunningUnitTests` verifies mock outcomes, transitions, eligibility, currency/overpayment guards, Day 1/3/7 schedule calculations, retry exhaustion, and `CANCELLED`/`EXPIRED` protection.
- `PaymentModuleIT` verifies Flyway `V1`–`V7`, Hibernate `ddl-auto=validate`, PostgreSQL immutability triggers, 6-thread concurrent payment settlement, clock-stepped Day 1 -> Day 3 -> Day 7 -> `FINAL_WARNING` -> `SUSPENDED` (`0` 4th retry), recovery from every dunning state, concurrent `SKIP LOCKED` dunning worker execution, `event_publication` outbox persistence, and MockMvc RBAC/customer isolation on Testcontainers PostgreSQL `17.12`.

---

## 7. Related Documents

- [ADR-007: Simulated Payment Processing Model](./ADR-007-simulated-payment-processing-model.md)
- [ADR-010: Financial Precision, Currency, and Rounding Policy](./ADR-010-financial-precision-currency-and-rounding-policy.md)
- [ADR-012: Subscription State Transitions, Billing Periods, and Proration](./ADR-012-subscription-workflow-billing-periods-and-proration.md)
- [ADR-014: Billing, Invoicing, Tax, Credits, and Batch Execution](./ADR-014-billing-invoicing-tax-credits-and-batch-execution.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
