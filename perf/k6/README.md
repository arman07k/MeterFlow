# MeterFlow k6 Performance & QA Verification Suite (`perf/k6/`)

This directory contains reproducible `k6` scripts validating the Metering Engine against **Local Docker Compose PostgreSQL** (`local` profile only — **never** run load tests against shared Supabase PostgreSQL).

## Scripts

1. [`qa1-one-million-reconciliation.js`](./qa1-one-million-reconciliation.js) — **QA1 (1,000,000 Events & Independent Reconciliation)**:
   - Deterministic dataset of `1,000,000` submitted events (`1,000` batches of `1,000` events, configurable via `TOTAL_EVENTS` & `BATCH_SIZE`).
   - Exact deterministic composition: **`3%` identical duplicates**, **`2%` valid late events** (within the `72h` post-close window), and **`95%` unique on-time events**.
   - Computes the exact expected mathematical sum and unique count independently from the deterministic generator formula, then queries `POST /api/v1/metering/usage/query` and `POST /api/v1/metering/rollups/reconcile` to verify **zero double counting** and exact aggregate parity.
2. [`qa2-throughput-latency.js`](./qa2-throughput-latency.js) — **QA2 (Throughput >= 2,000 events/sec & p95 <= 100ms)**:
   - Uses `k6` `constant-arrival-rate` executor submitting batched payloads against `POST /api/v1/events/batch`.
   - Records custom `k6` trends distinguishing end-to-end HTTP ingestion latency (`http_req_duration`), per-event effective throughput (`meterflow_events_ingested`), and pairs with Micrometer `/actuator/metrics/meterflow.metering.ingestion.duration` and `/actuator/metrics/meterflow.metering.rollup.duration` to separate API latency vs. database latency.
3. [`qa3-restart-recovery.js`](./qa3-restart-recovery.js) — **QA3 (Interruption, Restart, Retry & Recovery)**:
   - Submits overlapping deterministic batches before, during, and after a simulated application container restart (`docker compose restart app`), retries failed/timed-out batches with identical idempotency keys, invokes `POST /api/v1/metering/rollups/recover` and `POST /api/v1/metering/rollups/reconcile`, and asserts exact duplicate-free recovery and aggregate parity.

## Prerequisites & Execution

1. Start Local Docker Compose stack (`meterflow-postgres` + `meterflow-app` with `local` profile).
2. Provision an operator account (`METERFLOW_BOOTSTRAP_ADMIN_*`), a customer (`CUSTOMER_ID`), an active subscription (`SUBSCRIPTION_PERIOD_START`), and a `SUM` billable metric (`METRIC_ID`).
3. Run via host `k6` or Dockerized `grafana/k6`:

```bash
k6 run \
  -e BASE_URL=http://127.0.0.1:18080 \
  -e BASIC_AUTH_USER=ops@meterflow.example \
  -e BASIC_AUTH_PASS="<operator-password>" \
  -e CUSTOMER_ID="<customer-uuid>" \
  -e METRIC_ID="<metric-uuid>" \
  -e PERIOD_START_ISO="2026-05-01T00:00:00Z" \
  perf/k6/qa2-throughput-latency.js
```
