# MeterFlow — Usage-Based Metering, Rating, Invoicing & Webhook Platform

MeterFlow is a production-minded **usage-based billing, subscription management, invoicing, dunning, and outbound webhook platform** built as a **Spring Modulith** modular monolith on **Java 21/25**, **Spring Boot 4.0.5**, **PostgreSQL 17**, **Flyway**, **Spring Batch 6**, **Spring Security 7**, **Caffeine**, and **Thymeleaf**.

All financial arithmetic uses an immutable `Money` value object backed by exact `BigDecimal` precision (`HALF_UP`, zero floating-point types), supporting **BDT** (paisa) and **USD** (cents) with strict single-currency isolation.

---

## Key Capabilities

| Requirement | Capability | Implementation Module |
|---|---|---|
| **FR1** | High-throughput batch event ingestion (up to 1,000 events/request), database-enforced `(customer_id, idempotency_key)` deduplication, 72-hour late-event window | `com.meterflow.metering` |
| **FR2** | Deterministic billable metrics (`COUNT`, `SUM`, `UNIQUE_COUNT`, `MAXIMUM`, `LATEST_VALUE`), property filters, hourly rollups + raw-event reconciliation | `com.meterflow.catalog`, `com.meterflow.metering` |
| **FR3** | Immutable published plan versions (`DRAFT` -> `PUBLISHED`), 6 pricing strategies (`FLAT_FEE`, `PER_UNIT`, `GRADUATED_TIERS`, `VOLUME_TIERS`, `PACKAGE`, `PER_SEAT`), free allowances, minimum commitments, OCP extensibility | `com.meterflow.catalog` |
| **FR4** | Subscriptions with trials, immediate & scheduled plan/seat changes, exact UTC proration, pause/resume/cancel, past-due & suspension state transitions | `com.meterflow.subscriptions` |
| **FR5** | Two-step invoicing (`DRAFT` -> `FINALIZED` -> `PAID` / `VOID`), sequential invoice numbering, PostgreSQL immutability triggers, tax strategies, chunked Spring Batch 6 runs with checkpoint resume | `com.meterflow.billing` |
| **FR6** | Prepaid credit wallets with expiry/priority consumption, immutable credit notes, late-usage & manual billing adjustments | `com.meterflow.billing` |
| **FR7** | Live spend & usage estimates (Caffeine-cached, clearly separated from finalized billing), deduplicated 50%/80%/100% usage threshold alerts | `com.meterflow.metering` |
| **FR8** | Internal mock payment processor (zero paid gateways), deterministic Day 1 / Day 3 / Day 7 dunning retries, separate suspension grace step, recovery reactivation, `FOR UPDATE SKIP LOCKED` worker | `com.meterflow.payments` |
| **FR9** | Outbound webhooks (`invoice.finalised`, `payment.failed`, `usage.threshold_reached`, etc.) with AES-256-GCM secret encryption, dual-key HMAC-SHA256 rotation (24h grace), SSRF validation, `SKIP LOCKED` delivery queue with exponential backoff (`+30s/+2m/+10m/+1h/+6h`), auditable manual replay, persistent customer notifications & immutable audit logs | `com.meterflow.webhooks`, `com.meterflow.notifications` |
| **FR10** | Strictly read-only (`@Transactional(readOnly = true)`) historical invoice & plan-version simulator reusing authoritative usage, rating, and tax engines without mutating any financial record | `com.meterflow.billing` |
| **UI** | Server-rendered Thymeleaf **Customer Portal** (`/customer/**`) and **Admin/Operator Console** (`/operator/**`, `/admin/**`) under strict `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'` and CSRF protection | `com.meterflow.portal` |

---

## Architecture Overview

MeterFlow enforces **10 strictly encapsulated Spring Modulith modules** verified automatically by `ModularityTests`:

```
portal  ──► identity::api, catalog::api, subscriptions::api, metering::api,
            billing::api, payments::api, webhooks::api, notifications::api
notifications ──► identity::api, billing::api, payments::api, metering::api, webhooks::api
webhooks      ──► identity::api, billing::api, payments::api, metering::api
payments      ──► identity::api, subscriptions::api, billing::api
billing       ──► identity::api, catalog::api, subscriptions::api, metering::api
metering      ──► identity::api, catalog::api, subscriptions::api
subscriptions ──► identity::api, catalog::api
catalog       ──► identity::api
identity      ──► shared
shared        ──► (kernel: Money, SupportedCurrency, Outbox, Batch, Cache, CorrelationIds)
```

Every cross-module interaction goes through an explicit `::api` named interface or transactional Spring Modulith outbox domain events (`event_publication`). Direct cross-module repository or internal class access fails the build.

See full architecture docs in [docs/architecture/](docs/architecture/SYSTEM_DESIGN.md) and ADRs in [docs/architecture/ADRs/README.md](docs/architecture/ADRs/README.md).

---

## Database Migrations (Flyway `V1`–`V8`)

Hibernate runs with `spring.jpa.hibernate.ddl-auto: validate` against forward-only Flyway migrations:

- `V1__infrastructure_foundation.sql` — Spring Modulith `event_publication` outbox + Spring Batch 6 metadata tables
- `V2__identity_and_security_schema.sql` — `customers`, `users`, `customer_memberships`
- `V3__catalog_and_pricing_schema.sql` — `billable_metrics`, `metric_filters`, `pricing_plans`, `plan_versions`, `plan_charges`, `charge_tiers`, `free_allowances`, `minimum_commitments`
- `V4__subscriptions_schema.sql` — `subscriptions`, plan assignments, scheduled changes, proration records, state transitions
- `V5__metering_schema.sql` — `usage_events`, `usage_rollups`, late events, rejected events, threshold evaluations
- `V6__billing_and_invoicing_schema.sql` — `billing_runs`, `tax_policies`, `invoices`, `invoice_lines`, `invoice_tax_lines`, `prepaid_credit_grants`, `credit_transactions`, `credit_notes`, `credit_note_lines`, `billing_adjustments` + immutability triggers
- `V7__payments_and_dunning_schema.sql` — `dunning_policies`, `dunning_cases`, `payment_transactions`, `payment_attempts`, `dunning_events` + immutability triggers
- `V8__webhooks_and_notifications_schema.sql` — `webhook_endpoints`, `webhook_deliveries`, `webhook_delivery_attempts` (immutable), `notifications`, `audit_logs` (immutable)

---

## Running Locally

### Prerequisites
- **JDK 21+** (tested on **Java 25** and **Java 21** via `-Pjava21`)
- **Docker** (for PostgreSQL 17 and Testcontainers)

### Option 1: Full Docker Compose Stack (PostgreSQL 17 + Application)

```bash
cp .env.example .env
docker compose --profile full up --build -d
```

Verify readiness:
```bash
curl http://localhost:8080/actuator/health/readiness
```

### Option 2: Local PostgreSQL + Maven Spring Boot Run

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Initial Administrator Bootstrap
MeterFlow never ships hardcoded credentials. To provision an initial `ADMIN` account on first boot, set these environment variables once (then disable):
```bash
METERFLOW_BOOTSTRAP_ADMIN_ENABLED=true
METERFLOW_BOOTSTRAP_ADMIN_EMAIL=admin@example.com
METERFLOW_BOOTSTRAP_ADMIN_NAME="Platform Administrator"
METERFLOW_BOOTSTRAP_ADMIN_PASSWORD="StrongPassword#2026"
```

---

## Running Tests & Verification

```bash
# Full build, Spring Modulith verification, unit tests, and PostgreSQL 17 Testcontainers ITs
./mvnw -B clean verify

# Java 21 compatibility verification
./mvnw -B -Pjava21 clean test-compile

# Opt-in full-scale QA1 (1,000,000 events) & QA3 (10,000 customers) PostgreSQL 17 benchmarks
./mvnw -B test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=QaScaleBenchmarkIT -Dmeterflow.scale.benchmark=true
```

### Verified Test Suite Summary (`100 / 100` Core + `2 / 2` Opt-In Scale Benchmarks)
- **Surefire (63 tests)**: `ModularityTests` (10 modules), `MoneyUnitTests`, `SharedFoundationUnitTests`, `IdentityUnitTests`, `SecurityFoundationTests`, `MetricDefinitionUnitTests`, `PricingEngineUnitTests`, `BillingPeriodAndProrationUnitTests`, `MeteringUnitTests`, `BillingUnitTests`, `PaymentAndDunningUnitTests`, `WebhookSecurityAndBackoffUnitTests`.
- **Failsafe PostgreSQL 17 Testcontainers (37 core + 2 opt-in scale)**: `MeterFlowContextAndFlywayIT`, `IdentityAndSecurityIT`, `CatalogModuleIT`, `SubscriptionModuleIT`, `MeteringModuleIT`, `BillingModuleIT`, `PaymentModuleIT`, `WebhookNotificationAndSimulationIT`, `PortalUiIT`, `QaEngineeringValidationIT`, plus opt-in `QaScaleBenchmarkIT`.

---

## Portals & API Surfaces

- **Home & Login**: `/` and `/login`
- **Customer Portal** (`ROLE_CUSTOMER`, strict tenant isolation):
  - `/customer/dashboard`, `/customer/usage`, `/customer/subscription`, `/customer/invoices`, `/customer/invoices/{id}`, `/customer/payments` (read-only), `/customer/credits`, `/customer/notifications`, `/customer/webhooks`, `/customer/simulation`
- **Admin / Operator Console** (`ROLE_ADMIN` / `ROLE_OPERATOR`):
  - `/operator/overview`, `/operator/customers`, `/operator/subscriptions`, `/operator/billing`, `/operator/payments`, `/operator/webhooks`, `/operator/catalog`, `/operator/simulation`
- **Versioned REST API** (`/api/v1/**`, stateless HTTP Basic, RFC 9457 `ProblemDetail` errors):
  - OpenAPI & Swagger UI (authenticated staff): `/v3/api-docs` and `/swagger-ui.html`

---

## Webhook Signing & Receiver Verification

Every outbound webhook POST includes:
- `X-MeterFlow-Signature: t=<unix_epoch_seconds>,v1=<hex_hmac>[,v1=<previous_hex_hmac>]`
- `X-MeterFlow-Delivery-Id`, `X-MeterFlow-Event-Type`, `X-MeterFlow-Event-Version`, `X-MeterFlow-Idempotency-Key`

The signature is `HMAC-SHA256(secret, "<epoch_seconds>.<raw_utf8_body>")`. During a 24-hour secret rotation grace window, MeterFlow includes both active and previous `v1=` signatures. Receivers must verify using constant-time comparison and reject timestamps skewed by more than 300 seconds. See `com.meterflow.webhooks.api.WebhookSignatureVerifier` for the reference Java implementation.

---

## Engineering & Quality Attribute Evidence (`QA1`–`QA5`)

Measured directly via `QaScaleBenchmarkIT` and `QaEngineeringValidationIT` on local Docker PostgreSQL 17 Testcontainers (zero mocks):
- **QA1 (One-Million-Event Accuracy & Reconciliation)**: **`1,000,000` unique events** + **`30,000` duplicates (3%)** + **`20,000` late events (2%)** across 10 multi-tenant customers (`0` errors, `143 MiB` heap used) reconciled with **0.00 paisa** deviation (`raw == hourly rollups == finalized invoices` at exact `6,050.00 BDT` per customer).
- **QA2 (Ingestion Throughput & Latency)**: Sustained multi-threaded ingestion (**`16,586.5 events/sec`**, `1,000,000` events in `60.29 s`, `0%` error rate); realistic batch-250 latency **`p50 = 35 ms`, `p95 = 46 ms`, `p99 = 49 ms`** (`<= 100 ms` p95 achieved); maximum-payload batch-1,000 latency under 4-thread contention **`p50 = 204 ms`, `p95 = 295 ms`, `p99 = 371 ms`** *(note: over stateless HTTP Basic with per-request BCrypt, HTTP `p95` reflects ~100 ms BCrypt verification cost)*.
- **QA3 (10,000-Customer Spring Batch Billing)**: **`10,000` customers/subscriptions** billed end-to-end via Spring Batch 6 (`chunk = 100`, `O(N)` keyset pagination) in **`5m 42.2s` (`342.2 s`)** — **`181.6 s` draft generation + `160.6 s` finalization** (well within the **10-minute / 600 s** target, `163 MiB` heap used), with `197 ms` idempotent re-run producing **`0` duplicate invoices**.
- **QA4 (Extensibility & Security)**: Custom 7th `ChargePricingStrategy` verified without modifying existing pricing code; strict RBAC, CSRF, CSP, SSRF guard, AES-256-GCM secret encryption, clamped `PageResponse` pagination, and tenant isolation verified across REST and portal tests.
- **QA5 (Auditability & Estimate Accuracy)**: Live spend estimate vs. finalized invoice deviation measured at **`0.0000%`** (`< 1%` target), with full JSONB calculation provenance on every invoice line and verified read-only `FR10` historical simulation.

---

## Additional Documentation

- [Defence Presentation Guide](docs/DEFENCE_GUIDE.md)
- [Requirements Traceability Matrix](docs/REQUIREMENTS_TRACEABILITY.md)
- [System Design & C4 Architecture](docs/architecture/SYSTEM_DESIGN.md)
- [Architecture Decision Records (ADRs)](docs/architecture/ADRs/README.md)
- [Changelog](CHANGELOG.md)
