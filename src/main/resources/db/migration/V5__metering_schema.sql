-- V5: Metering Engine — Usage Events, Idempotency, Hourly Rollups, Late-Event Tracking & Threshold Evaluations (FR1, FR2, FR7, ADR-013)
-- Sole DDL authority: Flyway (spring.jpa.hibernate.ddl-auto=validate)
-- Preserves V1, V2, V3, and V4 unchanged; compatible identically across Local Docker Compose, Testcontainers, and Supabase PostgreSQL.

-- ============================================================================
-- 1. Authoritative Immutable Usage Events (DB-Enforced Idempotency & Audit)
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_events (
    id                   UUID            NOT NULL,
    customer_id          UUID            NOT NULL,
    subscription_id      UUID,
    metric_id            UUID            NOT NULL,
    idempotency_key      VARCHAR(150)    NOT NULL,
    payload_hash         VARCHAR(64)     NOT NULL,
    quantity             NUMERIC(19, 6)  NOT NULL,
    measured_value       NUMERIC(19, 6),
    distinct_key         VARCHAR(255),
    event_timestamp      TIMESTAMPTZ     NOT NULL,
    ingested_at          TIMESTAMPTZ     NOT NULL,
    properties           JSONB           NOT NULL DEFAULT '{}'::jsonb,
    filter_matched       BOOLEAN         NOT NULL DEFAULT TRUE,
    late_event           BOOLEAN         NOT NULL DEFAULT FALSE,
    late_classification  VARCHAR(32)     NOT NULL DEFAULT 'ON_TIME',
    billing_period_start TIMESTAMPTZ,
    billing_period_end   TIMESTAMPTZ,
    processing_status    VARCHAR(24)     NOT NULL DEFAULT 'ROLLED_UP',
    CONSTRAINT pk_usage_events PRIMARY KEY (id),
    CONSTRAINT fk_usage_events_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_events_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_events_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE RESTRICT,
    CONSTRAINT uq_usage_events_customer_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT chk_usage_events_idem_nonblank CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT chk_usage_events_hash_len CHECK (char_length(payload_hash) = 64),
    CONSTRAINT chk_usage_events_quantity_nonneg CHECK (quantity >= 0),
    CONSTRAINT chk_usage_events_late_class CHECK (
        late_classification IN ('ON_TIME', 'LATE_WITHIN_72H')
    ),
    CONSTRAINT chk_usage_events_status CHECK (
        processing_status IN ('PENDING_ROLLUP', 'ROLLED_UP', 'RECONCILED')
    ),
    CONSTRAINT chk_usage_events_period_window CHECK (
        (billing_period_start IS NULL AND billing_period_end IS NULL)
        OR (billing_period_start IS NOT NULL AND billing_period_end IS NOT NULL AND billing_period_end > billing_period_start)
    )
);

-- Core composite index for window aggregation, UNIQUE_COUNT, LATEST_VALUE tie-breaking, and QA5 traceability
CREATE INDEX IF NOT EXISTS idx_usage_events_cust_metric_ts
    ON usage_events (customer_id, metric_id, event_timestamp ASC);

-- Partial index for downstream Billing late-event scans
CREATE INDEX IF NOT EXISTS idx_usage_events_late_only
    ON usage_events (customer_id, metric_id, event_timestamp ASC)
    WHERE late_event = TRUE;

-- Partial index for restart recovery of any unrolled events
CREATE INDEX IF NOT EXISTS idx_usage_events_pending_rollup
    ON usage_events (ingested_at ASC)
    WHERE processing_status = 'PENDING_ROLLUP';

-- GIN index accelerating ad-hoc JSONB dimensional filter queries
CREATE INDEX IF NOT EXISTS idx_usage_events_props_gin
    ON usage_events USING GIN (properties jsonb_path_ops);

-- ============================================================================
-- 2. Hourly Transactional Usage Rollups (O(hours) Reads + Exact Reconciliation)
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_rollups (
    id                     UUID            NOT NULL DEFAULT gen_random_uuid(),
    customer_id            UUID            NOT NULL,
    metric_id              UUID            NOT NULL,
    bucket_start           TIMESTAMPTZ     NOT NULL,
    bucket_end             TIMESTAMPTZ     NOT NULL,
    event_count            BIGINT          NOT NULL DEFAULT 0,
    sum_value              NUMERIC(24, 6)  NOT NULL DEFAULT 0,
    max_value              NUMERIC(19, 6),
    latest_value           NUMERIC(19, 6),
    latest_event_timestamp TIMESTAMPTZ,
    latest_ingested_at     TIMESTAMPTZ,
    latest_event_id        UUID,
    late_event_count       BIGINT          NOT NULL DEFAULT 0,
    last_reconciled_at     TIMESTAMPTZ,
    updated_at             TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_usage_rollups PRIMARY KEY (id),
    CONSTRAINT fk_usage_rollups_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_rollups_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE RESTRICT,
    CONSTRAINT uq_usage_rollups_bucket UNIQUE (customer_id, metric_id, bucket_start),
    CONSTRAINT chk_usage_rollups_bucket CHECK (bucket_end > bucket_start),
    CONSTRAINT chk_usage_rollups_counts CHECK (event_count >= 0 AND late_event_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_usage_rollups_cust_metric_bucket
    ON usage_rollups (customer_id, metric_id, bucket_start DESC);

-- ============================================================================
-- 3. Late-Event Billing Adjustment Queue / Traceability Contract (FR1, FR6)
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_late_event_records (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    usage_event_id      UUID            NOT NULL,
    customer_id         UUID            NOT NULL,
    subscription_id     UUID,
    metric_id           UUID            NOT NULL,
    event_timestamp     TIMESTAMPTZ     NOT NULL,
    ingested_at         TIMESTAMPTZ     NOT NULL,
    closed_period_start TIMESTAMPTZ,
    closed_period_end   TIMESTAMPTZ     NOT NULL,
    hours_late          NUMERIC(10, 4)  NOT NULL,
    adjustment_status   VARCHAR(24)     NOT NULL DEFAULT 'PENDING_BILLING',
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    consumed_at         TIMESTAMPTZ,
    CONSTRAINT pk_usage_late_event_records PRIMARY KEY (id),
    CONSTRAINT uq_usage_late_event_id UNIQUE (usage_event_id),
    CONSTRAINT fk_usage_late_event FOREIGN KEY (usage_event_id)
        REFERENCES usage_events (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_late_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_late_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_late_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE RESTRICT,
    CONSTRAINT chk_usage_late_hours CHECK (hours_late >= 0 AND hours_late <= 72.0000),
    CONSTRAINT chk_usage_late_status CHECK (adjustment_status IN ('PENDING_BILLING', 'CONSUMED_BY_BILLING'))
);

CREATE INDEX IF NOT EXISTS idx_usage_late_records_pending
    ON usage_late_event_records (customer_id, adjustment_status, created_at ASC);

-- ============================================================================
-- 4. Rejected / Conflicting Usage Event Audit Log (No Raw Sensitive Payloads)
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_rejected_events (
    id               UUID          NOT NULL DEFAULT gen_random_uuid(),
    customer_id      UUID,
    metric_id        UUID,
    idempotency_key  VARCHAR(150),
    rejection_code   VARCHAR(48)   NOT NULL,
    rejection_reason VARCHAR(500)  NOT NULL,
    event_timestamp  TIMESTAMPTZ,
    payload_hash     VARCHAR(64),
    rejected_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_usage_rejected_events PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_usage_rejected_customer_at
    ON usage_rejected_events (customer_id, rejected_at DESC);

-- ============================================================================
-- 5. Deduplicated Usage Threshold Evaluations (FR7: 50%, 80%, 100% Foundation)
-- ============================================================================
CREATE TABLE IF NOT EXISTS usage_threshold_evaluations (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),
    customer_id         UUID            NOT NULL,
    subscription_id     UUID,
    metric_id           UUID,
    scope_key           VARCHAR(120)    NOT NULL,
    window_start        TIMESTAMPTZ     NOT NULL,
    window_end          TIMESTAMPTZ     NOT NULL,
    threshold_percent   INTEGER         NOT NULL,
    limit_value         NUMERIC(19, 6)  NOT NULL,
    current_value       NUMERIC(24, 6)  NOT NULL,
    utilization_percent NUMERIC(10, 4)  NOT NULL,
    triggered_at        TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_usage_threshold_evaluations PRIMARY KEY (id),
    CONSTRAINT fk_usage_threshold_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_threshold_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_usage_threshold_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE RESTRICT,
    CONSTRAINT uq_usage_threshold_once UNIQUE (customer_id, scope_key, window_start, window_end, threshold_percent),
    CONSTRAINT chk_usage_threshold_pct CHECK (threshold_percent IN (50, 80, 100)),
    CONSTRAINT chk_usage_threshold_window CHECK (window_end > window_start),
    CONSTRAINT chk_usage_threshold_values CHECK (limit_value > 0 AND current_value >= 0)
);

CREATE INDEX IF NOT EXISTS idx_usage_threshold_customer_window
    ON usage_threshold_evaluations (customer_id, window_start DESC);
