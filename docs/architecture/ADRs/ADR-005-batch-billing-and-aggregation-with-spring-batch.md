# ADR-005: Two-Step Chunk-Oriented Draft and Finalize Batch Invoicing with Spring Batch

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-005` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR1`, `FR3`–`FR6`, `QA1`, `QA2`, `QA5`, `C5`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

Period-close invoicing (`FR5`) must aggregate usage across all `5` aggregation types (`FR2`), evaluate prorated mid-cycle `subscription_segments` (`FR4`), rate all `6` charge models with free allowances and minimum commitments (`FR3`), apply pending `late_usage_adjustments` (`FR6`), calculate tax, draw down unexpired `credit_grants` (`FR6`), and persist full line-level provenance—while respecting `FR1`'s **72-hour late-event grace window** and scaling to `1,000+` subscriptions in `<= 60 seconds` (`QA1`).

---

## 2. Decision Drivers

- **Compatibility with `72-Hour` Late-Event Grace Window (`FR1`, `FR5`)**: Allow periods closing at `period_end` to sit in `DRAFT` state until `grace_expires_at` (`period_end + 72h` by default) before locking immutable `FINALIZED` invoices.
- **Throughput & Bounded Memory (`QA1`, `SC-QA1-4`)**: Process `1,000` subscriptions (`>= 100,000` events) in `<= 60 s` with `< 512 MB` heap.
- **Crash Restartability & Zero Double-Billing (`QA2`, `SC-QA2-4`)**: Resume failed runs cleanly at chunk granularity (`chunk = 100`).
- **Modern Spring Batch 6 Compliance**: Use `JobBuilder`/`StepBuilder` with explicit Flyway `BATCH_*` tables.

---

## 3. Considered Options

### Option A: Two Coordinated Spring Batch 6 Chunk Jobs (`generateDraftInvoicesJob` + `finalizeInvoicesJob`)
- **Pros**: Cleanly separates mutable grace-window `DRAFT` calculation from irreversible `FINALIZED` numbering, credit-wallet drawdown, and payment/webhook triggers; chunk checkpointing (`chunk = 100`) bounds heap and lock duration; reuses `catalog.api.RatingEngineFacade`.
- **Cons**: Requires managing `BATCH_*` metadata via Flyway `V1` and ensuring `DRAFT` regeneration is idempotent.

### Option B: Immediate Single-Step Finalization at `period_end`
- **Pros**: One batch job instead of two.
- **Cons**: Forces every slightly delayed event (`1m`–`72h` late) into post-finalization `late_usage_adjustments` instead of natural `DRAFT` inclusion (`FR1`, `FR5`).

### Option C: Single `@Transactional` Loop Without Spring Batch
- **Pros**: Less configuration.
- **Cons**: Violates mandatory stack decisions and fails `SC-QA1-4` / `SC-QA2-4` on large subscription counts or transient DB errors.

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Two-Step Chunk-Oriented Batch Invoicing with Spring Batch 6 (`generateDraftInvoicesJob` and `finalizeInvoicesJob`)**.

1. **`generateDraftInvoicesJob` (`chunk = 100` by `subscription_id`)**: Creates or refreshes `DRAFT` invoices (`grace_expires_at = period_end + grace_period_hours`), calling `UsageAggregationFacade` and `RatingEngineFacade`, attaching pending `late_usage_adjustments`, and writing traceable `invoice_lines`.
2. **`finalizeInvoicesJob` (`chunk = 100`)**: Selects `DRAFT` invoices where `grace_expires_at <= clock.instant()` (or explicitly finalized by an operator), computes `invoice_tax_lines`, draws down eligible `credit_grants` in `(expires_at ASC NULLS LAST, priority ASC, created_at ASC)` order, assigns sequential `invoice_number`, locks `status = FINALIZED`, and publishes `InvoiceFinalizedEvent`.
3. **Post-Finalization Late Fallback (`FR1`, `FR6`)**: If an operator finalized an invoice manually before `72h` elapsed and a valid `<= 72h` late event arrives afterward, `PriorPeriodLateEventRecorded` triggers cumulative-delta `late_usage_adjustments` without touching the `FINALIZED` invoice.

---

## 5. Consequences

### Positive Consequences
- Seamless harmony between `FR1` (`72h` late events), `FR5` (`DRAFT` vs. `FINALIZED` immutability), and `FR6` (credits & late adjustments).
- Restartable, observable chunk execution (`BATCH_STEP_EXECUTION` counters).

### Negative Consequences and Trade-Offs
- Requires two job definitions; both share common `BillingRepository` infrastructure inside `com.meterflow.billing`.

### Risks and Mitigations
- **Risk**: Concurrent `finalizeInvoicesJob` and `PriorPeriodLateEventRecorded` listener touching the same `DRAFT` invoice.
  - **Mitigation**: Guard `invoices` with `@Version` optimistic locking (`version BIGINT`) and `SELECT ... FOR UPDATE` on invoice status transition.

---

## 6. Compliance and Verification

- `SpringBatchTest` suite verifies `DRAFT` generation, grace-window late-event refresh, `FINALIZED` immutability, FIFO credit drawdown, and mid-chunk crash restart on Testcontainers PostgreSQL (`SC-QA1-4`, `SC-QA2-4`, `C9`).

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [ADR-004: PostgreSQL Transactional Outbox](./ADR-004-postgresql-transactional-outbox-messaging.md)
