# ADR-009: Outbound HMAC-SHA256 Webhooks, Dual-Key Rotation, Exponential Backoff, History, and Authorized Replay in PostgreSQL

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-009` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR9`, `QA2`, `QA4`, `QA5`, `C6`, `C7`, `C8`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

`FR9` requires reliable outbound webhooks for three mandatory event types—**`invoice.finalised`**, **`payment.failed`**, and **`usage.threshold_reached`** (plus optional extensible billing/dunning events)—with cryptographic `HMAC-SHA256` signing, safe signing-secret rotation, exponential backoff retry, persistent per-delivery status and attempt logs, and authorized manual replay.

Because **Kafka, RabbitMQ, Redis, and paid webhook SaaS platforms are prohibited** (`C7`), and executing slow third-party HTTP retries directly inside Spring Modulith's internal `event_publication` sweeper ([ADR-004](./ADR-004-postgresql-transactional-outbox-messaging.md)) would starve internal billing/dunning listeners, MeterFlow isolates outbound HTTP delivery into a dedicated PostgreSQL-backed `webhooks` module.

---

## 2. Decision Drivers

- **Exact Mandatory Event Coverage (`FR9`)**: Deliver `invoice.finalised`, `payment.failed`, and `usage.threshold_reached` reliably.
- **Cryptographic Authenticity & Zero-Downtime Secret Rotation (`FR9`, `QA4`, `C6`)**: Sign payloads with `HMAC-SHA256`, verify in constant time with replay-window skew protection, encrypt secrets at rest, and support dual-key rotation without breaking in-flight deliveries.
- **Isolation & Exponential Backoff (`QA2`, `C7`, `C8`)**: Use PostgreSQL `FOR UPDATE SKIP LOCKED` (`30s`..`6h` backoff, `5s` timeout) without Kafka or Redis.
- **Complete Auditability & Replay Authorization (`FR9`, `QA4`)**: Persist every delivery status and attempt record; enforce strict RBAC and tenant scoping on manual replay.

---

## 3. Considered Options

### Option A: Dedicated `webhooks` Module with PostgreSQL `FOR UPDATE SKIP LOCKED` Queue (`webhook_deliveries` + `webhook_delivery_attempts`) and Dual-Key `HMAC-SHA256` Signing
- **Pros**: Internal `@ApplicationModuleListener` inserts `webhook_deliveries` rows and completes `event_publication` in `< 5 ms`; dedicated worker polls `idx_webhook_deliveries_due` (`SKIP LOCKED`), signs with active (+ grace-period previous) encrypted secret, enforces `5s` timeouts and SSRF IP checks, logs immutable attempts, and enforces RBAC on manual replay (`C7`, `C8`).
- **Cons**: Requires three dedicated tables (`webhook_endpoints`, `webhook_deliveries`, `webhook_delivery_attempts`) inside `com.meterflow.webhooks`.

### Option B: Direct HTTP Delivery Inside `@ApplicationModuleListener` Relying Only on `event_publication`
- **Pros**: Fewer tables.
- **Cons**: Flat `15s` retry cadence violates exponential backoff, blocks internal outbox threads during receiver outages, and lacks per-attempt HTTP status/latency history and authorized replay.

### Option C: External Broker or Paid Webhook SaaS (Kafka / Redis / Svix)
- **Pros**: Offloaded delivery.
- **Cons**: **Explicitly prohibited** (`No Kafka`, `No Redis`, `No paid cloud services`, `C7`).

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Dedicated `webhooks` Module Backed by PostgreSQL `FOR UPDATE SKIP LOCKED` Delivery & Attempt History Tables**.

### Protocol, Secret Rotation & Replay Authorization Rules

1. **Mandatory Event Catalog**:
   - `invoice.finalised` (mapped from `InvoiceFinalizedEvent`, `FR5`)
   - `payment.failed` (mapped from `InvoicePaymentFailedEvent` on initial failure and Retries 1, 2, and 3, `FR8`)
   - `usage.threshold_reached` (mapped from `BudgetThresholdExceededEvent`, `FR7`)
2. **HMAC-SHA256 Signing & Receiver Verification Strategy**:
   - Canonical signed input: `<unix_epoch_seconds>.<raw_utf8_json_body>`
   - Outbound headers:
     - `X-MeterFlow-Signature: t=<unix_epoch_seconds>,v1=<hex_hmac_active>[,v1=<hex_hmac_previous>]`
     - `X-MeterFlow-Event-Id: <source_event_uuid>` (preserved across retries and manual replays for receiver deduplication)
     - `X-MeterFlow-Delivery-Id: <delivery_uuid>`
   - **Receiver Verification Strategy**: (a) Extract `t` and all `v1` values; (b) reject if `|now - t| > 300 seconds` (replay protection); (c) compute `HMAC-SHA256(secret, t + "." + raw_body_bytes)`; (d) compare against each `v1` digest using **constant-time comparison** (`MessageDigest.isEqual`), accepting if any matches.
3. **Safe Signing-Secret Rotation (Dual-Key Grace Window)**:
   - `webhook_endpoints` stores `active_signing_secret_enc` and nullable `previous_signing_secret_enc` + `previous_secret_expires_at` encrypted at rest with an externalized `AES-256-GCM` envelope key (`WEBHOOK_SECRET_ENCRYPTION_KEY`); secrets are never logged or redisplayed after initial creation/rotation.
   - Rotating a secret moves `active -> previous`, sets `previous_secret_expires_at = clock.instant().plus(Duration.ofHours(24))`, and generates a new 256-bit cryptographically random `active` secret. While `previous_secret_expires_at > clock.instant()`, outgoing deliveries emit **both** `v1=<active>` and `v1=<previous>` signatures so receivers migrate with zero downtime; expired previous secrets are excluded and nulled out.
4. **Exponential Backoff & Persistent Attempt Logs**:
   - Statuses on `webhook_deliveries`: `PENDING` -> `RETRYING` -> `DELIVERED` or `EXHAUSTED`.
   - Retry offsets on non-`2xx` or `5s` timeout: `+30s`, `+2m`, `+10m`, `+1h`, `+6h` (`max 6` attempts total -> `EXHAUSTED`).
   - Every attempt inserts an immutable `webhook_delivery_attempts` row (`attempt_number`, `is_manual_replay`, `replayed_by_user_id`, `signature_timestamp`, `http_status`, `duration_ms`, `response_excerpt` `<= 500` chars, `error_reason`, `attempted_at`).
5. **Manual Replay & Replay Authorization**:
   - Replaying resets delivery for immediate dispatch, preserves original `source_event_id` and `payload`, generates a **fresh `t=<current_epoch>` signature** (so receivers' `300s` skew check passes), and records `is_manual_replay = true` + `replayed_by_user_id` + an entry in `audit_logs`.
   - **Authorization**: `ROLE_ADMIN` and `ROLE_OPERATOR` may replay any delivery targeting an `active = true` endpoint; `ROLE_CUSTOMER` may view and replay **only** deliveries whose `webhook_endpoints.customer_id == principal.customerId()` (`403`/`404` on cross-tenant ID; `409 Conflict` if endpoint is disabled).
6. **SSRF Defense**: `SsrfUrlValidator` resolves target IPs at both registration and dispatch time (preventing DNS rebinding), blocking loopback, link-local (`169.254.0.0/16`), and private RFC-1918 networks unless `meterflow.webhooks.allow-local-targets=true` is explicitly set in `local`/`test` profiles.

---

## 5. Consequences

### Positive Consequences
- Full compliance with every `FR9` sub-requirement (`invoice.finalised`, `payment.failed`, `usage.threshold_reached`, HMAC signing, dual-key rotation, backoff, history, authorized replay) using zero external brokers (`C7`, `C8`).

### Negative Consequences and Trade-Offs
- Requires managing `WEBHOOK_SECRET_ENCRYPTION_KEY` in environment variables alongside `SUPABASE_DB_*` (`C6`).

### Risks and Mitigations
- **Risk**: Receivers fail to handle multiple `v1=` signatures during rotation.
  - **Mitigation**: Document reference constant-time multi-`v1` verifier clearly in portal webhook documentation and allow configuring `0h` immediate cutover if desired.

---

## 6. Compliance and Verification

- Local JDK `HttpServer` integration test verifies `invoice.finalised`, `payment.failed`, and `usage.threshold_reached` payloads, dual-key rotation verification (both old and new keys succeed during grace window; old key rejected after `Clock` passes `+24h`), `503 -> 503 -> 200` backoff + `3` attempt log rows, fresh-timestamp replay, and `403`/`404` on cross-customer replay (`FR9`, `SC-QA2-3`, `SC-QA4-1`, `SC-QA4-2`, `C9`).

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
- [`DATA_MODEL.md`](../DATA_MODEL.md)
- [ADR-004: PostgreSQL Transactional Outbox](./ADR-004-postgresql-transactional-outbox-messaging.md)
