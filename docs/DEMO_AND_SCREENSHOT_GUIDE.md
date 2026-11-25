# MeterFlow — Live Demonstration Runbook & Screenshot Capture Checklist (`docs/DEMO_AND_SCREENSHOT_GUIDE.md`)

> **Asset Status Note:** No UI screenshots are bundled in this repository yet. Per submission integrity rules, no artificial or mock screenshots have been generated. Use **Section 5** below to capture authentic screenshots from a live local run for your final report or presentation slides.

---

## 1. Clean Startup & Prerequisites

### Prerequisites
- **JDK 21+** (tested on Temurin Java 25 and Java 21 via `-Pjava21`)
- **Docker Desktop / Docker Engine** with Docker Compose

### Option A — Full Docker Compose Stack (Recommended for Live Demo)

```bash
cp .env.example .env
```

Edit `.env` locally (never commit `.env`) to bootstrap an initial administrator on first boot:

```dotenv
SERVER_PORT=18080
WEBHOOK_SECRET_ENCRYPTION_KEY=<base64-encoded-32-byte-key>
METERFLOW_BOOTSTRAP_ADMIN_ENABLED=true
METERFLOW_BOOTSTRAP_ADMIN_EMAIL=admin@meterflow.demo
METERFLOW_BOOTSTRAP_ADMIN_NAME=Platform Administrator
METERFLOW_BOOTSTRAP_ADMIN_PASSWORD=DemoAdminPass#2026
```

*(Tip: `SERVER_PORT=18080` avoids Windows Hyper-V/WinNAT dynamic port exclusions that sometimes block port `8080`.)*

Start PostgreSQL 17 + MeterFlow:

```bash
docker compose --profile full up --build -d
```

Verify readiness:

```bash
curl http://localhost:18080/actuator/health/readiness
# Expected: {"status":"UP"}
```

After the first boot creates `admin@meterflow.demo`, set `METERFLOW_BOOTSTRAP_ADMIN_ENABLED=false` in `.env` (idempotent on restart, but disabling is best practice).

### Option B — Local PostgreSQL + Maven Run

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

---

## 2. Prepared Demo Data Requirements

MeterFlow starts with an empty domain schema (aside from the seeded `DEFAULT_DAY_1_3_7` dunning policy in `V7` and your bootstrapped `ADMIN` user). Before presenting, prepare:

1. **One Customer Account** (e.g., `acme-cloud`, `Acme Cloud Ltd`, currency `BDT`) created via **Operator Console -> `/operator/customers`** (`POST /operator/customers`).
2. **One Customer User + Membership** (`ROLE_CUSTOMER` linked to `acme-cloud`) — note that customer user registration is exposed via `IdentityManagementFacade` (or seeded via SQL/dev harness during setup; see `IdentityAndSecurityIT` for the exact `users` + `customer_memberships` relationship).
3. **One Billable Metric** (e.g., `api_calls`, aggregation `SUM`, target property `quantity`) via `POST /api/v1/catalog/metrics` (authenticated as `ADMIN` using HTTP Basic).
4. **Two Published Pricing Plan Versions** (e.g., `PRO-PLAN` `v1` with `FLAT_FEE` `1,000.00 BDT` + `GRADUATED_TIERS` / `PER_UNIT` `0.50 BDT`, and `PRO-PLAN` `v2` cloned & published with lower tier pricing for the `FR10` comparison demo) via `POST /api/v1/catalog/plans`, `PUT /api/v1/catalog/plan-versions/{id}`, and `POST /api/v1/catalog/plan-versions/{id}/publish` (or publish/clone from `/operator/catalog`).
5. **One Active Subscription** for `acme-cloud` on `PRO-PLAN v1` via `POST /api/v1/subscriptions`.
6. **One Tax Policy** (e.g., `15%` VAT for `BDT`) via `/operator/billing` (`POST /operator/billing/tax-policies`) and an optional **Prepaid Credit Grant** (e.g., `200.00 BDT`) via `/operator/billing`.
7. **Optional Local Webhook Sink** (e.g., `python -m http.server` or a tiny local HTTP listener on `http://localhost:9099/webhook`, allowed under `local` profile) registered on `/customer/webhooks` for `invoice.finalised`, `payment.failed`, and `usage.threshold_reached`.

---

## 3. Recommended Live Demonstration Sequence (10–12 Minutes)

| Step | Surface & URL | Action to Perform | Expected Result |
| :--- | :--- | :--- | :--- |
| **1. Architecture & Health** | `/actuator/health/readiness` & `/` | Show healthy app (`UP`) and sign in at `/login` as `ADMIN` | Redirects into authenticated portal; inspect browser DevTools Response Headers to show strict `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'` |
| **2. Operator Overview & Catalogue (`FR2`, `FR3`, `FR10`)** | `/operator/overview`, `/operator/catalog` | Show active KPIs, immutable `PUBLISHED` plan version `v1`, click **Clone** to create `v2` draft, and show `PUBLISHED` versions cannot be edited | New `DRAFT` `v2` created independently; `v1` remains locked by service guard + PostgreSQL trigger |
| **3. Idempotent Batch Event Ingestion (`FR1`, `FR2`)** | REST `POST /api/v1/events/batch` (Swagger `/swagger-ui.html` or `curl`) | Submit a batch of usage events; immediately resubmit the exact same batch (`idempotency_key`), then resubmit one key with a mutated quantity | First call inserts & rolls up events; identical replay returns `DUPLICATE` (`0` double-counting); mutated payload returns `409 Conflict` (`IDEMPOTENCY_KEY_CONFLICT`) |
| **4. Live Customer Spend Estimate (`FR7`)** | `/customer/dashboard` & `/customer/usage` | Sign in as `ROLE_CUSTOMER` in an incognito window; show live estimated spend & recent usage updating within seconds | Live estimate reflects ingested events cleanly (`15s` Caffeine TTL + after-commit eviction) and shows customer-scoped data only |
| **5. Two-Step Spring Batch Billing & Credits (`FR5`, `FR6`)** | `/operator/billing` | Trigger **Draft Generation Batch**, inspect generated `DRAFT` invoice, then trigger **Finalization Batch** (or finalize single invoice) | Sequential `INV-<YYYY>-<seq>` assigned, `15%` tax computed, prepaid credits drawn FIFO, invoice locked immutable, `invoice.finalised` outbox event emitted |
| **6. Line-Level Traceability (`FR5`)** | `/customer/invoices` -> `/customer/invoices/{id}` | Open the finalized invoice in Customer Portal | Displays itemized base fee, usage charge, allowances, tax lines, credits applied, and exact `BDT` totals |
| **7. Mock Payment Failure & Dunning (`FR8`)** | `/operator/payments` & `/customer/payments` | Initiate a `SIMULATE_SOFT_DECLINE` mock payment on the finalized invoice, then trigger **Process Dunning**, then initiate `SIMULATE_SUCCESS` | Soft decline opens a Day 1/3/7 dunning case (`RETRY_SCHEDULED`), emits `payment.failed` notification & webhook; customer sees read-only payment status (no simulation controls); subsequent `SIMULATE_SUCCESS` marks invoice `PAID` and recovers dunning case |
| **8. Signed Webhooks & Auditable Replay (`FR9`)** | `/customer/webhooks` & `/operator/webhooks` | Show one-time secret disclosure / secret rotation (`24h` grace window), inspect delivery attempt log, and click **Replay** | Fresh `HMAC-SHA256` signed delivery attempt appended; immutable row recorded in `audit_logs` |
| **9. Read-Only Historical Simulation (`FR10`)** | `/customer/simulation` & `/operator/simulation` | Select the finalized historical invoice and compare against `PRO-PLAN v2` | Exact `Money` difference & `%` delta displayed with zero database mutations; customer cannot select `DRAFT` plans |
| **10. Tenant Isolation Proof (`QA4`)** | `/customer/invoices/{otherId}` or `/operator/overview` as `ROLE_CUSTOMER` | Attempt accessing operator URL or another customer's UUID as a customer user | Strictly blocked with `403 Forbidden` |

---

## 4. Recovery Steps for Common Demo Problems

| Issue | Likely Cause | Fast Recovery |
| :--- | :--- | :--- |
| `Bind for 0.0.0.0:8080 failed` on Windows | Hyper-V / WinNAT excluded port range includes `8080` | Set `SERVER_PORT=18080` in `.env` and re-run `docker compose --profile full up -d` |
| `meterflow-app` fails startup with `WEBHOOK_SECRET_ENCRYPTION_KEY` error | Key is not valid Base64 decoding to exactly 32 bytes (or blank in `supabase` profile) | Generate a valid 32-byte key (`openssl rand -base64 32` or PowerShell `[Convert]::ToBase64String((1..32 \| ForEach-Object { Get-Random -Max 256 }))`) and set in `.env` |
| Admin login fails on fresh database | `METERFLOW_BOOTSTRAP_ADMIN_ENABLED` was `false` or password `< 12` characters | Set `METERFLOW_BOOTSTRAP_ADMIN_ENABLED=true` with a `>= 12`-char password in `.env` and restart `meterflow-app` once |
| Need a clean reset between rehearsals | Stale demo records in Docker PostgreSQL volume | Run `docker compose --profile full down -v && docker compose --profile full up -d` to recreate a clean PostgreSQL volume and re-apply Flyway `V1`–`V8` |
| Webhook endpoint rejected with `422` locally | `METERFLOW_WEBHOOKS_ALLOW_LOCAL_TARGETS` is `false` outside `local` profile | Ensure `SPRING_PROFILES_ACTIVE=local` (which sets `allow-local-targets: true` for local demos) |

---

## 5. Manual Screenshot Capture Checklist

Capture the following real browser screens at `1440x900` (recommended) after running the demo flow above, and store them in an assets folder (e.g., `docs/assets/`) if you choose to embed them in your submission report:

- [ ] **01 — Landing & Sign-In (`/login`)**: Shows branded sign-in form and clean server-rendered layout.
- [ ] **02 — Operator Overview (`/operator/overview`)**: Shows tenant count, subscription status counts, webhook queue status, recent Spring Batch billing runs, and active dunning cases.
- [ ] **03 — Operator Catalogue (`/operator/catalog`)**: Shows immutable `PUBLISHED` plan versions alongside a cloned `DRAFT` version.
- [ ] **04 — Operator Billing & Batch Runs (`/operator/billing`)**: Shows `DRAFT_GENERATION` / `FINALIZATION` batch runs, tax policies, prepaid credit grants, and finalized invoices.
- [ ] **05 — Operator Payments & Dunning (`/operator/payments`)**: Shows mock payment outcomes (`SIMULATE_SUCCESS` / `SIMULATE_SOFT_DECLINE`) and Day 1/3/7 dunning case progress.
- [ ] **06 — Operator Webhooks & Audit Log (`/operator/webhooks`)**: Shows outbound webhook deliveries, attempt statuses, and immutable audit entries after replay.
- [ ] **07 — Customer Dashboard (`/customer/dashboard`)**: Shows active subscription, live spend estimate, prepaid credit balance, recent invoices, and notifications.
- [ ] **08 — Customer Usage & Live Estimate (`/customer/usage`)**: Shows metered usage events and current-period estimate breakdown.
- [ ] **09 — Customer Invoice Detail (`/customer/invoices/{id}`)**: Shows sequential `INV-*` number, itemized charges, tax lines, credits applied, and exact `BDT`/`USD` totals.
- [ ] **10 — Customer Webhooks (`/customer/webhooks`)**: Shows endpoint list, subscribed events, secret prefix/rotation controls, and delivery history.
- [ ] **11 — Customer Historical Plan Simulation (`/customer/simulation`)**: Shows side-by-side historical invoice vs. alternative `PUBLISHED` plan delta (read-only `FR10`).
- [ ] **12 — OpenAPI / Swagger UI (`/swagger-ui.html`)**: Logged in as `ADMIN`/`OPERATOR`, showing grouped `/api/v1/**` endpoints.
