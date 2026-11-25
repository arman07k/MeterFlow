-- Flyway V8: Outbound Webhooks, Delivery Queue, Notifications, and Audit Log Schema (FR9, ADR-009)
-- Additive forward-only migration; preserves V1 through V7 untouched.

-- 1. Webhook Endpoints (encrypted signing secrets, dual-key rotation, soft delete)
CREATE TABLE webhook_endpoints (
    id                          UUID PRIMARY KEY,
    customer_id                 UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    name                        VARCHAR(120) NOT NULL,
    target_url                  VARCHAR(1000) NOT NULL,
    subscribed_events           JSONB NOT NULL,
    active_signing_secret_enc   TEXT NOT NULL,
    secret_prefix               VARCHAR(24) NOT NULL,
    previous_signing_secret_enc TEXT,
    previous_secret_expires_at  TIMESTAMPTZ,
    active                      BOOLEAN NOT NULL DEFAULT TRUE,
    deleted_at                  TIMESTAMPTZ,
    version                     BIGINT NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_webhook_endpoints_events_array CHECK (jsonb_typeof(subscribed_events) = 'array'),
    CONSTRAINT chk_webhook_endpoints_prev_secret CHECK (
        (previous_signing_secret_enc IS NULL AND previous_secret_expires_at IS NULL)
        OR (previous_signing_secret_enc IS NOT NULL AND previous_secret_expires_at IS NOT NULL)
    )
);

CREATE INDEX idx_webhook_endpoints_customer_created
    ON webhook_endpoints (customer_id, created_at DESC);

CREATE INDEX idx_webhook_endpoints_customer_active
    ON webhook_endpoints (customer_id)
    WHERE active = TRUE AND deleted_at IS NULL;

-- 2. Persistent Outbound Webhook Delivery Queue (PostgreSQL FOR UPDATE SKIP LOCKED)
CREATE TABLE webhook_deliveries (
    id                  UUID PRIMARY KEY,
    webhook_endpoint_id UUID NOT NULL REFERENCES webhook_endpoints (id) ON DELETE RESTRICT,
    customer_id         UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    source_event_id     UUID NOT NULL,
    event_type          VARCHAR(64) NOT NULL,
    event_version       VARCHAR(16) NOT NULL DEFAULT '1.0',
    idempotency_key     VARCHAR(150) NOT NULL,
    payload_json        TEXT NOT NULL,
    status              VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'RETRYING', 'DELIVERED', 'EXHAUSTED')),
    attempt_count       INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts        INTEGER NOT NULL DEFAULT 6 CHECK (max_attempts >= 1),
    next_attempt_at     TIMESTAMPTZ,
    last_attempted_at   TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    last_http_status    INTEGER,
    last_error_reason   VARCHAR(500),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_webhook_deliveries_endpoint_event UNIQUE (webhook_endpoint_id, source_event_id)
);

CREATE INDEX idx_webhook_deliveries_due
    ON webhook_deliveries (next_attempt_at ASC, id ASC)
    WHERE status IN ('PENDING', 'RETRYING');

CREATE INDEX idx_webhook_deliveries_customer_created
    ON webhook_deliveries (customer_id, created_at DESC);

CREATE INDEX idx_webhook_deliveries_endpoint_created
    ON webhook_deliveries (webhook_endpoint_id, created_at DESC);

-- 3. Immutable Append-Only Webhook Delivery Attempt Log
CREATE TABLE webhook_delivery_attempts (
    id                  UUID PRIMARY KEY,
    webhook_delivery_id UUID NOT NULL REFERENCES webhook_deliveries (id) ON DELETE RESTRICT,
    webhook_endpoint_id UUID NOT NULL REFERENCES webhook_endpoints (id) ON DELETE RESTRICT,
    customer_id         UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    attempt_number      INTEGER NOT NULL CHECK (attempt_number >= 1),
    is_manual_replay    BOOLEAN NOT NULL DEFAULT FALSE,
    replayed_by_user_id UUID,
    signature_timestamp BIGINT NOT NULL,
    http_status         INTEGER,
    duration_ms         INTEGER NOT NULL CHECK (duration_ms >= 0),
    response_excerpt    VARCHAR(500),
    error_reason        VARCHAR(500),
    attempted_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_delivery_attempts_delivery
    ON webhook_delivery_attempts (webhook_delivery_id, attempted_at ASC);

CREATE OR REPLACE FUNCTION prevent_webhook_delivery_attempt_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'webhook_delivery_attempts rows are immutable and append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_webhook_delivery_attempts_immutable
    BEFORE UPDATE OR DELETE ON webhook_delivery_attempts
    FOR EACH ROW
    EXECUTE FUNCTION prevent_webhook_delivery_attempt_mutation();

-- 4. Persistent Customer Notifications
CREATE TABLE notifications (
    id                UUID PRIMARY KEY,
    customer_id       UUID NOT NULL REFERENCES customers (id) ON DELETE RESTRICT,
    notification_type VARCHAR(48) NOT NULL,
    title             VARCHAR(160) NOT NULL,
    message           VARCHAR(500) NOT NULL,
    resource_type     VARCHAR(48),
    resource_id       UUID,
    source_event_id   UUID,
    is_read           BOOLEAN NOT NULL DEFAULT FALSE,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_notifications_customer_event
    ON notifications (customer_id, notification_type, source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE INDEX idx_notifications_customer_read_created
    ON notifications (customer_id, is_read, created_at DESC);

-- 5. Immutable Append-Only Operational & Security Audit Logs
CREATE TABLE audit_logs (
    id            UUID PRIMARY KEY,
    actor_user_id UUID,
    actor_email   VARCHAR(255),
    customer_id   UUID,
    action        VARCHAR(80) NOT NULL,
    resource_type VARCHAR(48) NOT NULL,
    resource_id   UUID,
    details       JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_occurred
    ON audit_logs (occurred_at DESC);

CREATE INDEX idx_audit_logs_customer_occurred
    ON audit_logs (customer_id, occurred_at DESC)
    WHERE customer_id IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_audit_log_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs rows are immutable and append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW
    EXECUTE FUNCTION prevent_audit_log_mutation();
