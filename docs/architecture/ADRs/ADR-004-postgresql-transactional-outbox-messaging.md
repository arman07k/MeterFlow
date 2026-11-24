# ADR-004: Reliable Internal Domain Messaging via PostgreSQL-Backed Transactional Outbox Instead of Kafka

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-004` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR1`, `FR4`, `FR6`–`FR9`, `QA2`, `QA3`, `C7`, `C8`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow modules must react reliably to cross-domain state transitions without cyclic compile-time dependencies (`QA3`) and without losing events on crashes (`QA2`):
- `PriorPeriodLateEventRecorded` (`metering` -> `billing` for `DRAFT` refresh or `FR6` cumulative-delta late adjustment).
- `BudgetThresholdExceededEvent` (`metering` -> `notifications` & `webhooks`, `FR7`).
- `InvoiceFinalizedEvent` / `CreditNoteIssuedEvent` (`billing` -> `payments`, `notifications`, `webhooks`, `FR5`–`FR9`).
- `PaymentCapturedEvent` / `DunningTerminalActionEvent` / `DunningRecoveredEvent` (`payments` -> `billing`, `subscriptions`, `notifications`, `webhooks`, `FR8`).

**Apache Kafka and external message brokers are strictly prohibited** (`C7`).

---

## 2. Decision Drivers

- **Atomic Dual-Write Immunity (`QA2`, `C8`)**: Commit aggregate changes and event publications inside one ACID PostgreSQL transaction.
- **Strict Broker Prohibition (`C7`)**: Zero Kafka, RabbitMQ, NATS, or paid cloud queues.
- **Cycle-Free Module Graph (`QA3`, `C1`)**: Decouple `metering -> billing` and `payments -> billing/subscriptions` cleanly.
- **Separation of Fast Internal Listeners from Slow External HTTP Calls (`FR9`)**: Prevent failing third-party webhook servers from blocking internal outbox completion.

---

## 3. Considered Options

### Option A: Spring Modulith PostgreSQL Event Publication Registry (`event_publication`) for Internal Listeners + Dedicated PostgreSQL Queue (`ADR-009`) for External HTTP Webhooks
- **Pros**: Atomic single-DB commit; automatic per-listener completion tracking; `IncompleteEventPublications` retry sweeper recovers transient internal listener faults within `<= 30s`; hands off external HTTP webhooks to `webhook_deliveries` in `< 5 ms` ([ADR-009](./ADR-009-outbound-hmac-webhooks-backoff-and-replay.md)).
- **Cons**: Adds compact `INSERT`/`UPDATE` writes to `event_publication` per domain event.

### Option B: Apache Kafka Event Bus
- **Pros**: High-throughput multi-service streaming.
- **Cons**: **Explicitly prohibited** (`No Kafka`, `C7`).

### Option C: In-Memory `@Async @EventListener` Without Outbox Table
- **Pros**: Zero DB write overhead.
- **Cons**: Loses late-event adjustments, payment settlements, and webhook triggers on JVM crash (`QA2`, `C8`).

---

## 4. Decision Outcome

**Chosen Option**: **Option A — PostgreSQL-Backed Transactional Outbox via `spring-modulith-starter-jpa` (`event_publication`)**.

1. Provision `event_publication` and partial index `idx_event_publication_incomplete` via Flyway `V1` (`C5`).
2. Publish immutable Java `record` domain events inside `@Transactional` domain methods; do **not** emit raw per-event outbox rows on normal current-period `ON_TIME` usage events (which update `usage_rollups` directly), reserving outbox publications for meaningful state transitions, `<= 72h` closed-period late arrivals, budget crossings, invoices, payments, and dunning transitions.
3. Enforce consumer idempotency (`uq_notification_event`, `trigger_usage_event_id UNIQUE` on `late_usage_adjustments`, conditional state checks on `invoices`) and run a `15s` `@Scheduled` `IncompleteEventPublications` retry sweeper (`<= 30s` recovery target).

---

## 5. Consequences

### Positive Consequences
- `100%` crash-durable internal event delivery with zero external brokers (`C7`, `C8`, `SC-QA2-3`).
- Avoids high-volume outbox bloat by publishing domain transition events rather than echoing every routine `ON_TIME` raw usage event.

### Negative Consequences and Trade-Offs
- Requires periodic cleanup of old completed `event_publication` rows (`CompletedEventPublications.deletePublicationsOlderThan(Duration.ofDays(7))`).

### Risks and Mitigations
- **Risk**: Deterministic bug in a listener causes repeated retries.
  - **Mitigation**: Bound retry age (`< 24h`) in the sweeper and expose incomplete outbox counts via Actuator metrics (`QA5`).

---

## 6. Compliance and Verification

- Confirm `spring-modulith-starter-jpa` presence and zero `kafka`/`rabbit` dependencies (`C7`).
- Testcontainers fault-injection test proves failed listener leaves `completion_date IS NULL` and completes within `<= 30s` on retry with zero duplicate side effects (`SC-QA2-3`, `C9`).

---

## 7. Related Documents

- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [ADR-009: Outbound HMAC Webhooks, Backoff, and Replay](./ADR-009-outbound-hmac-webhooks-backoff-and-replay.md)
