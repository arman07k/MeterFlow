# ADR-014: Invoicing, Tax Policy, Prepaid Credits, Credit Notes, Late Adjustments, and Spring Batch Execution

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-014` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR5`, `FR6`, `FR10`, `QA1`, `QA2`, `QA3`, `QA4`, `QA5`, `C1`, `C5`, `C7`, `C8`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow requires an authoritative **Invoicing & Billing Engine** (`com.meterflow.billing`) that transforms subscription periods, proration records, and authoritative metered usage into financially sound `DRAFT` and immutable `FINALIZED` invoices (`FR5`), calculates deterministic tax, manages prepaid credit balances and immutable credit notes (`FR6`), processes post-finalization `72-hour` late usage adjustments without double billing (`FR1`, `FR6`), and executes repeatable, restart-safe bulk billing runs on Spring Batch 6 (`ADR-005`).

All billing logic must strictly reuse `shared.Money` (`ADR-010`), `catalog.api.RatingEngineFacade` (`ADR-011`), `subscriptions.api.SubscriptionQueryFacade` (`ADR-012`), and `metering.api.UsageQueryFacade`/`LateUsageQueryFacade` (`ADR-013`) with zero duplicated rating, aggregation, or authorization code, and without consulting Caffeine caches as financial truth.

---

## 2. Decision Drivers

- **Immutability of Finalized Financial Records (`FR5`, `QA2`)**: Finalized invoices, invoice lines, tax lines, and issued credit notes must be protected both in application logic and via PostgreSQL `BEFORE UPDATE/DELETE` triggers.
- **Zero Double Billing & Zero Credit Overdrafts (`QA2`)**: Repeated or interrupted billing runs must never create duplicate invoices for `(subscription_id, period_start, period_end)` or double-consume prepaid credits.
- **Exact Monetary Precision (`ADR-010`)**: Persist line/tax/credit/ledger amounts in `NUMERIC(19,4)` (`Money.roundToPersisted()`) and final customer settlement amounts in `NUMERIC(19,2)` (`Money.roundToPayable()`, `HALF_UP` paisa/cent) with strict `BDT`/`USD` currency consistency.
- **Restartable Bounded-Memory Batch Processing (`ADR-005`, `QA1`)**: Use Spring Batch 6 chunk-oriented jobs (`generateDraftInvoicesJob` and `finalizeInvoicesJob`, `chunk = 100`) backed by Flyway `BATCH_*` metadata tables.
- **Strict Scope & Cost-Free Compliance (`C7`)**: Zero external tax APIs, zero real payment gateways, zero Redis/Kafka/H2.

---

## 3. Considered Options

### Option A — Mutable Single-Step Invoices Without Database Immutability Enforcement
- Rejected: Violates `FR5` auditability and allows late events or retries to silently overwrite finalized invoices.

### Option B — Two-Step `DRAFT` -> `FINALIZED` Billing Engine with DB Triggers, Pessimistic Credit Locking, Extensible Tax SPI, and Spring Batch 6 Chunk Jobs (Selected)
- Selected: Cleanly separates grace-window draft generation from irreversible finalization, guarantees zero double billing via partial unique constraints, prevents credit overdrafts via `SELECT ... FOR UPDATE` + per-invoice idempotency keys, and integrates directly with Spring Batch 6 and Spring Modulith's transactional outbox.

---

## 4. Decision Outcome

### 4.1 Two-Step Invoice Workflow & Database Immutability (`V6__billing_schema.sql`)
- **States**: `DRAFT` -> `FINALIZED` -> `PAID` | `PAST_DUE` | `WRITTEN_OFF` | `VOID` (and `DRAFT -> VOID`).
- **Duplicate Prevention**: Partial unique index `uq_invoices_sub_period_active ON invoices (subscription_id, period_start, period_end) WHERE status <> 'VOID'` guarantees at most one active invoice per subscription billing period across concurrent or retried runs.
- **Database Triggers**: `trg_guard_finalized_invoice_immutable`, `trg_guard_invoice_lines_immutable`, and `trg_guard_invoice_tax_lines_immutable` enforce at the PostgreSQL layer that once an invoice leaves `DRAFT`, its `invoice_number`, `subtotal_amount`, `taxable_amount`, `tax_amount`, `total_amount`, `credits_applied_amount`, `finalized_at`, `invoice_lines`, and `invoice_tax_lines` can never be mutated or deleted. Only post-finalization settlement status and `credit_notes_applied_amount`/`amount_due` reductions are permitted.
- **Sequential Numbering**: Finalization assigns a unique sequential `invoice_number` (`INV-<YYYY>-<seq>`) from PostgreSQL `invoice_number_seq`.

### 4.2 Authoritative Line Generation & Traceability (`FR5`)
- Draft generation reads the subscription's plan assignments and proration records from `SubscriptionQueryFacade.getSubscriptionHistory`, loads the immutable `PUBLISHED` plan version from `PlanCatalogFacade`, queries authoritative PostgreSQL usage via `UsageQueryFacade.queryMetricUsage` (never Caffeine `liveEstimates`), and delegates all six charge types (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`), free allowances, and minimum commitments exclusively to `RatingEngineFacade.rate`.
- Each `invoice_lines` row persists `subscription_id`, `plan_version_id`, `charge_id`, `charge_code`, `charge_type`, `metric_id`, `window_start`, `window_end`, `seat_quantity`, `raw_quantity`, `free_allowance_quantity`, `billable_quantity`, `unit_price`, `line_amount`, and `calculation_breakdown JSONB`.
- Any unbilled mid-cycle `ProrationRecord` within `[period_start, period_end)` and any `PENDING` `billing_adjustments` (including late usage adjustments) are attached as traceable invoice lines.
- Refreshing an existing `DRAFT` invoice atomically replaces its lines, tax lines, and totals without duplicating the invoice row.

### 4.3 Deterministic Extensible Tax Engine
- Explicit `tax_policies` table stores active customer-specific or global default tax rates (`rate_percent NUMERIC(7,4)`, `tax_exempt BOOLEAN`, `jurisdiction`, `tax_name`).
- `TaxCalculationStrategy` SPI (with default `StandardPercentageTaxStrategy`) computes deterministic `invoice_tax_lines` over `taxable_amount = SUM(taxable line_amount)` rounded once per tax line via `Money.roundToPersisted()`, cleanly separating `subtotal_amount`, `taxable_amount`, `tax_amount`, and `total_amount`.

### 4.4 Prepaid Credits, Credit Notes & Late Usage Adjustments (`FR6`)
- **Prepaid Credits (`prepaid_credit_grants`, `credit_transactions`)**: Issued with currency consistency and idempotency keys (`uq_credit_grants_idempotency`). During invoice finalization, eligible unexpired grants (`expires_at IS NULL OR expires_at > now`, matching currency, `remaining_amount > 0`) are locked with `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) in deterministic `(expires_at ASC NULLS LAST, priority ASC, created_at ASC, id ASC)` order and drawn down atomically with per-invoice transaction idempotency keys (`uq_credit_tx_idempotency`) and DB `CHECK (remaining_amount >= 0)`.
- **Credit Notes (`credit_notes`, `credit_note_lines`)**: Issued only against finalized invoices (`FINALIZED`, `PAID`, `PAST_DUE`) under `SELECT ... FOR UPDATE` on the original invoice. Enforces currency match, idempotency (`uq_credit_notes_idempotency`), and cumulative limit (`existing_credits + new_credit <= original_invoice.total_amount`). Reduces open `amount_due` down to `0` and issues any excess (e.g., on an already paid invoice) as a new traceable `PREPAID` credit grant.
- **Late Usage Adjustments (`billing_adjustments`)**: Consumes pending late records from `LateUsageQueryFacade`. If the affected period's invoice is still `DRAFT`, refreshes the draft; if already `FINALIZED`, computes cumulative-delta re-rating via `UsageQueryFacade` + `RatingEngineFacade`, records a `billing_adjustments` row guarded by `UNIQUE (late_usage_record_id)`, and marks the late record consumed in `metering` to guarantee zero double billing.

### 4.5 Spring Batch 6 Integration & Outbox Events
- Defines `generateDraftInvoicesJob` and `finalizeInvoicesJob` (`chunk = 100`) on Spring Batch 6 (`JobBuilder`, `StepBuilder`, `JobRepository`, `JobOperator`) using bounded paginated readers ordered deterministically by `id ASC` so interrupted runs resume cleanly without duplicate invoices or double credit drawdown.
- Publishes `InvoiceFinalizedEvent` and `CreditNoteIssuedEvent` (`com.meterflow.billing.api`) via Spring's `ApplicationEventPublisher` (persisted atomically in Spring Modulith's `event_publication` outbox for future Payments, Notifications, and Webhooks modules).

---

## 5. Consequences

### Positive
- Zero duplicated rating, aggregation, or security logic; full end-to-end auditability from raw metered events through invoice lines, tax lines, credits, and credit notes.
- PostgreSQL constraints, triggers, and pessimistic locks guarantee financial integrity under retries, crashes, and concurrent billing runs.

### Tradeoffs
- Pessimistic locking on a customer's credit grants during finalization serializes concurrent credit consumption for the *same* customer (zero cross-customer contention) in exchange for absolute overdraft immunity.
- Spring Batch 6 `chunk = 100` commits per chunk; individual invoice idempotency ensures safe replay if a chunk rolls back mid-run.

---

## 6. Compliance and Verification

- Verified by `BillingUnitTests` (tax strategy, invoice math, credit drawdown ordering, credit note caps, state transition rules, `BDT`/`USD` rounding), `ModularityTests` (`billing :: api` encapsulation and zero cycles), and `BillingModuleIT` (PostgreSQL 17 Testcontainers validating Flyway `V6`, Hibernate `ddl-auto=validate`, DB immutability triggers, concurrent billing & credit overdraft prevention, credit notes, late-usage delta adjustments, Spring Batch 6 chunk jobs & restartability, REST authorization/isolation, and RFC 9457 errors).

---

## 7. Related Documents

- [ADR-005](./ADR-005-batch-billing-and-aggregation-with-spring-batch.md)
- [ADR-010](./ADR-010-financial-precision-currency-and-rounding-policy.md)
- [ADR-011](./ADR-011-pricing-tiers-packages-allowances-and-commitments.md)
- [ADR-012](./ADR-012-subscription-workflow-billing-periods-and-proration.md)
- [ADR-013](./ADR-013-metering-ingestion-idempotency-rollups-and-freshness.md)
- [DATA_MODEL.md](../DATA_MODEL.md)
- [MODULE_BOUNDARIES.md](../MODULE_BOUNDARIES.md)
