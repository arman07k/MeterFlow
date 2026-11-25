-- V7: Payments & Dunning Engine — Mock Payment Transactions, Append-Only Attempts, Dunning Policies, Cases & Events (FR8, ADR-007, ADR-015)
-- Sole DDL authority: Flyway (spring.jpa.hibernate.ddl-auto=validate)
-- Preserves V1 through V6 unchanged; compatible identically across Local Docker Compose, Testcontainers, and Supabase PostgreSQL.

-- ============================================================================
-- 0. Forward Extension of Subscription Status Constraint to Support SUSPENDED
-- ============================================================================
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS chk_subscriptions_status;
ALTER TABLE subscriptions ADD CONSTRAINT chk_subscriptions_status CHECK (
    status IN ('TRIALING', 'ACTIVE', 'PAUSED', 'PAST_DUE', 'SUSPENDED', 'CANCELLED', 'EXPIRED')
);

ALTER TABLE subscription_state_transitions DROP CONSTRAINT IF EXISTS chk_sub_transitions_to_status;
ALTER TABLE subscription_state_transitions ADD CONSTRAINT chk_sub_transitions_to_status CHECK (
    to_status IN ('TRIALING', 'ACTIVE', 'PAUSED', 'PAST_DUE', 'SUSPENDED', 'CANCELLED', 'EXPIRED')
);

DROP INDEX IF EXISTS uq_subscriptions_one_open_per_customer;
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscriptions_one_open_per_customer
    ON subscriptions (customer_id)
    WHERE status IN ('TRIALING', 'ACTIVE', 'PAUSED', 'PAST_DUE', 'SUSPENDED');

-- ============================================================================
-- 1. Sequential Payment Reference Generator
-- ============================================================================
CREATE SEQUENCE IF NOT EXISTS payment_reference_seq START WITH 100001 INCREMENT BY 1;

-- ============================================================================
-- 2. Dunning Policies (Exact 3-Attempt Retry Offsets + Separate Suspension Offset)
-- ============================================================================
CREATE TABLE IF NOT EXISTS dunning_policies (
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    policy_code             VARCHAR(64)  NOT NULL,
    name                    VARCHAR(160) NOT NULL,
    retry_offsets_hours     JSONB        NOT NULL DEFAULT '[24, 72, 168]'::jsonb,
    suspension_offset_hours INTEGER      NOT NULL DEFAULT 336,
    active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_dunning_policies PRIMARY KEY (id),
    CONSTRAINT uq_dunning_policies_code UNIQUE (policy_code),
    CONSTRAINT chk_dunning_policies_retry_len CHECK (
        jsonb_typeof(retry_offsets_hours) = 'array' AND jsonb_array_length(retry_offsets_hours) = 3
    ),
    CONSTRAINT chk_dunning_policies_suspension_offset CHECK (suspension_offset_hours >= 168)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_dunning_policies_active_default
    ON dunning_policies ((1))
    WHERE active = TRUE;

INSERT INTO dunning_policies (policy_code, name, retry_offsets_hours, suspension_offset_hours, active)
VALUES ('DEFAULT_DAY_1_3_7', 'Default Day 1 / Day 3 / Day 7 Dunning Policy', '[24, 72, 168]'::jsonb, 336, TRUE)
ON CONFLICT (policy_code) DO NOTHING;

-- ============================================================================
-- 3. Dunning Cases (Persistent Restart-Safe Retry & Separate Suspension Timers)
-- ============================================================================
CREATE TABLE IF NOT EXISTS dunning_cases (
    id                  UUID         NOT NULL DEFAULT gen_random_uuid(),
    invoice_id          UUID         NOT NULL,
    customer_id         UUID         NOT NULL,
    subscription_id     UUID         NOT NULL,
    dunning_policy_id   UUID         NOT NULL,
    status              VARCHAR(24)  NOT NULL,
    initial_failure_at  TIMESTAMPTZ  NOT NULL,
    retries_completed   INTEGER      NOT NULL DEFAULT 0,
    next_retry_at       TIMESTAMPTZ,
    suspension_at       TIMESTAMPTZ,
    suspended_at        TIMESTAMPTZ,
    recovered_at        TIMESTAMPTZ,
    last_failure_code   VARCHAR(64),
    last_failure_reason VARCHAR(255),
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_dunning_cases PRIMARY KEY (id),
    CONSTRAINT uq_dunning_cases_invoice UNIQUE (invoice_id),
    CONSTRAINT fk_dunning_cases_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dunning_cases_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dunning_cases_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dunning_cases_policy FOREIGN KEY (dunning_policy_id)
        REFERENCES dunning_policies (id) ON DELETE RESTRICT,
    CONSTRAINT chk_dunning_cases_status CHECK (
        status IN ('RETRY_SCHEDULED', 'FINAL_WARNING', 'SUSPENDED', 'RECOVERED', 'CANCELLED')
    ),
    CONSTRAINT chk_dunning_cases_retries CHECK (retries_completed BETWEEN 0 AND 3)
);

CREATE INDEX IF NOT EXISTS idx_dunning_cases_due_retry
    ON dunning_cases (next_retry_at ASC, id ASC)
    WHERE status = 'RETRY_SCHEDULED' AND next_retry_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dunning_cases_due_suspend
    ON dunning_cases (suspension_at ASC, id ASC)
    WHERE status = 'FINAL_WARNING' AND suspension_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_dunning_cases_customer_created
    ON dunning_cases (customer_id, created_at DESC);

-- ============================================================================
-- 4. Payment Transactions (Authoritative Payment Records + Single-Capture Guard)
-- ============================================================================
CREATE TABLE IF NOT EXISTS payment_transactions (
    id                 UUID           NOT NULL DEFAULT gen_random_uuid(),
    payment_reference  VARCHAR(50)    NOT NULL,
    invoice_id         UUID           NOT NULL,
    customer_id        UUID           NOT NULL,
    subscription_id    UUID           NOT NULL,
    dunning_case_id    UUID,
    currency_code      VARCHAR(3)     NOT NULL,
    amount             NUMERIC(19, 4) NOT NULL,
    status             VARCHAR(24)    NOT NULL,
    attempt_source     VARCHAR(24)    NOT NULL,
    attempt_number     INTEGER        NOT NULL DEFAULT 0,
    simulation_outcome VARCHAR(32)    NOT NULL,
    retryable          BOOLEAN        NOT NULL DEFAULT FALSE,
    failure_code       VARCHAR(64),
    failure_reason     VARCHAR(255),
    idempotency_key    VARCHAR(150),
    completed_at       TIMESTAMPTZ,
    version            BIGINT         NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_payment_transactions PRIMARY KEY (id),
    CONSTRAINT uq_payment_transactions_ref UNIQUE (payment_reference),
    CONSTRAINT fk_payment_tx_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_tx_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_tx_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_tx_dunning_case FOREIGN KEY (dunning_case_id)
        REFERENCES dunning_cases (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_tx_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_payment_tx_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_tx_status CHECK (status IN ('PENDING', 'CAPTURED', 'FAILED')),
    CONSTRAINT chk_payment_tx_source CHECK (attempt_source IN ('MANUAL', 'AUTO_INITIAL', 'DUNNING_RETRY')),
    CONSTRAINT chk_payment_tx_attempt_num CHECK (attempt_number BETWEEN 0 AND 3),
    CONSTRAINT chk_payment_tx_outcome CHECK (
        simulation_outcome IN ('SIMULATE_SUCCESS', 'SIMULATE_SOFT_DECLINE', 'SIMULATE_HARD_DECLINE', 'SIMULATE_TIMEOUT')
    )
);

-- Database-level immunity against duplicate invoice settlement under concurrency (FR8, QA2)
CREATE UNIQUE INDEX IF NOT EXISTS uq_captured_payment_per_invoice
    ON payment_transactions (invoice_id)
    WHERE status = 'CAPTURED';

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_tx_idempotency
    ON payment_transactions (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_tx_invoice_created
    ON payment_transactions (invoice_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_payment_tx_customer_created
    ON payment_transactions (customer_id, created_at DESC);

-- ============================================================================
-- 5. Append-Only Payment Attempts Audit History
-- ============================================================================
CREATE TABLE IF NOT EXISTS payment_attempts (
    id                     UUID           NOT NULL DEFAULT gen_random_uuid(),
    payment_transaction_id UUID           NOT NULL,
    invoice_id             UUID           NOT NULL,
    customer_id            UUID           NOT NULL,
    dunning_case_id        UUID,
    attempt_number         INTEGER        NOT NULL DEFAULT 0,
    attempt_source         VARCHAR(24)    NOT NULL,
    simulation_outcome     VARCHAR(32)    NOT NULL,
    status                 VARCHAR(24)    NOT NULL,
    retryable              BOOLEAN        NOT NULL DEFAULT FALSE,
    failure_code           VARCHAR(64),
    failure_reason         VARCHAR(255),
    amount                 NUMERIC(19, 4) NOT NULL,
    currency_code          VARCHAR(3)     NOT NULL,
    idempotency_key        VARCHAR(150),
    attempted_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_payment_attempts PRIMARY KEY (id),
    CONSTRAINT fk_payment_attempts_tx FOREIGN KEY (payment_transaction_id)
        REFERENCES payment_transactions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempts_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempts_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_payment_attempts_case FOREIGN KEY (dunning_case_id)
        REFERENCES dunning_cases (id) ON DELETE RESTRICT,
    CONSTRAINT chk_payment_attempts_status CHECK (status IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_payment_attempts_source CHECK (attempt_source IN ('MANUAL', 'AUTO_INITIAL', 'DUNNING_RETRY')),
    CONSTRAINT chk_payment_attempts_amount CHECK (amount > 0),
    CONSTRAINT chk_payment_attempts_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_payment_attempts_outcome CHECK (
        simulation_outcome IN ('SIMULATE_SUCCESS', 'SIMULATE_SOFT_DECLINE', 'SIMULATE_HARD_DECLINE', 'SIMULATE_TIMEOUT')
    )
);

CREATE INDEX IF NOT EXISTS idx_payment_attempts_tx
    ON payment_attempts (payment_transaction_id, attempted_at ASC);

CREATE INDEX IF NOT EXISTS idx_payment_attempts_invoice
    ON payment_attempts (invoice_id, attempted_at ASC);

CREATE INDEX IF NOT EXISTS idx_payment_attempts_case
    ON payment_attempts (dunning_case_id, attempted_at ASC)
    WHERE dunning_case_id IS NOT NULL;

-- ============================================================================
-- 6. Append-Only Dunning Transition & Retry History
-- ============================================================================
CREATE TABLE IF NOT EXISTS dunning_events (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    dunning_case_id UUID        NOT NULL,
    invoice_id      UUID        NOT NULL,
    customer_id     UUID        NOT NULL,
    subscription_id UUID        NOT NULL,
    event_type      VARCHAR(36) NOT NULL,
    from_status     VARCHAR(24),
    to_status       VARCHAR(24) NOT NULL,
    attempt_number  INTEGER,
    scheduled_for   TIMESTAMPTZ,
    details         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_dunning_events PRIMARY KEY (id),
    CONSTRAINT fk_dunning_events_case FOREIGN KEY (dunning_case_id)
        REFERENCES dunning_cases (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dunning_events_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dunning_events_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_dunning_events_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_dunning_events_type CHECK (
        event_type IN (
            'INITIAL_FAILURE',
            'RETRY_SCHEDULED',
            'RETRY_ATTEMPTED',
            'RETRY_FAILED',
            'FINAL_WARNING_ISSUED',
            'SUBSCRIPTION_SUSPENDED',
            'RECOVERED'
        )
    )
);

CREATE INDEX IF NOT EXISTS idx_dunning_events_case_occurred
    ON dunning_events (dunning_case_id, occurred_at ASC);

-- ============================================================================
-- 7. PostgreSQL Immutability & Append-Only Guards for Completed Payments & History
-- ============================================================================
CREATE OR REPLACE FUNCTION guard_completed_payment_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Cannot delete financial payment transaction % (status=%)', OLD.id, OLD.status;
    END IF;
    IF OLD.status IN ('CAPTURED', 'FAILED') THEN
        RAISE EXCEPTION 'Cannot modify immutable completed payment transaction % (status=%)', OLD.id, OLD.status;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_completed_payment_immutable ON payment_transactions;
CREATE TRIGGER trg_guard_completed_payment_immutable
    BEFORE UPDATE OR DELETE ON payment_transactions
    FOR EACH ROW
    EXECUTE FUNCTION guard_completed_payment_immutable();

CREATE OR REPLACE FUNCTION guard_append_only_payment_audit()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Append-only payment/dunning audit table % prohibits % operations', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_payment_attempts_append_only ON payment_attempts;
CREATE TRIGGER trg_guard_payment_attempts_append_only
    BEFORE UPDATE OR DELETE ON payment_attempts
    FOR EACH ROW
    EXECUTE FUNCTION guard_append_only_payment_audit();

DROP TRIGGER IF EXISTS trg_guard_dunning_events_append_only ON dunning_events;
CREATE TRIGGER trg_guard_dunning_events_append_only
    BEFORE UPDATE OR DELETE ON dunning_events
    FOR EACH ROW
    EXECUTE FUNCTION guard_append_only_payment_audit();
