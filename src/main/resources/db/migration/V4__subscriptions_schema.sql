-- V4: Subscriptions, Plan Assignments, Scheduled Changes, Proration Audit Records & State Transitions (FR4, ADR-012)
-- Sole DDL authority: Flyway (spring.jpa.hibernate.ddl-auto=validate)
-- Preserves V1, V2, and V3 unchanged; compatible identically across Local Docker Compose, Testcontainers, and Supabase PostgreSQL.

-- ============================================================================
-- 1. Subscriptions (Core State, Trial Configuration, Billing Period & Locking)
-- ============================================================================
CREATE TABLE IF NOT EXISTS subscriptions (
    id                       UUID         NOT NULL DEFAULT gen_random_uuid(),
    customer_id              UUID         NOT NULL,
    current_plan_version_id  UUID         NOT NULL,
    currency_code            VARCHAR(3)   NOT NULL,
    billing_interval         VARCHAR(20)  NOT NULL,
    billing_timezone         VARCHAR(64)  NOT NULL DEFAULT 'UTC',
    seat_quantity            INTEGER      NOT NULL DEFAULT 1,
    status                   VARCHAR(20)  NOT NULL,
    trial_start_at           TIMESTAMPTZ,
    trial_end_at             TIMESTAMPTZ,
    trial_conversion_policy  VARCHAR(32)  NOT NULL DEFAULT 'EXPIRE_ON_END',
    current_period_start     TIMESTAMPTZ,
    current_period_end       TIMESTAMPTZ,
    paused_at                TIMESTAMPTZ,
    paused_remaining_seconds BIGINT,
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT FALSE,
    cancelled_at             TIMESTAMPTZ,
    creation_idempotency_key VARCHAR(150),
    version                  BIGINT       NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_subscriptions_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_subscriptions_plan_version FOREIGN KEY (current_plan_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_subscriptions_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_subscriptions_interval CHECK (billing_interval IN ('MONTHLY', 'ANNUAL')),
    CONSTRAINT chk_subscriptions_timezone_nonblank CHECK (btrim(billing_timezone) <> ''),
    CONSTRAINT chk_subscriptions_seat_nonneg CHECK (seat_quantity >= 0),
    CONSTRAINT chk_subscriptions_status CHECK (
        status IN ('TRIALING', 'ACTIVE', 'PAUSED', 'PAST_DUE', 'CANCELLED', 'EXPIRED')
    ),
    CONSTRAINT chk_subscriptions_trial_policy CHECK (
        trial_conversion_policy IN ('EXPIRE_ON_END', 'AUTO_CONVERT_TO_PAID')
    ),
    CONSTRAINT chk_subscriptions_trial_window CHECK (
        (trial_start_at IS NULL AND trial_end_at IS NULL)
        OR (trial_start_at IS NOT NULL AND trial_end_at IS NOT NULL AND trial_end_at > trial_start_at)
    ),
    CONSTRAINT chk_subscriptions_period_window CHECK (
        (current_period_start IS NULL AND current_period_end IS NULL)
        OR (current_period_start IS NOT NULL AND current_period_end IS NOT NULL AND current_period_end > current_period_start)
    ),
    CONSTRAINT chk_subscriptions_paused_remaining CHECK (
        paused_remaining_seconds IS NULL OR paused_remaining_seconds >= 0
    )
);

-- Single non-terminal subscription per customer policy (ADR-012)
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscriptions_one_open_per_customer
    ON subscriptions (customer_id)
    WHERE status IN ('TRIALING', 'ACTIVE', 'PAUSED', 'PAST_DUE');

CREATE UNIQUE INDEX IF NOT EXISTS uq_subscriptions_creation_idempotency
    ON subscriptions (customer_id, creation_idempotency_key)
    WHERE creation_idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_subscriptions_customer_created
    ON subscriptions (customer_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_subscriptions_due_trial_end
    ON subscriptions (trial_end_at ASC)
    WHERE status = 'TRIALING' AND trial_end_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_subscriptions_due_eop_cancel
    ON subscriptions (current_period_end ASC)
    WHERE status = 'ACTIVE' AND cancel_at_period_end = TRUE;

-- ============================================================================
-- 2. Immutable Plan-Version Assignment History / Segments (FR4, FR10)
-- ============================================================================
CREATE TABLE IF NOT EXISTS subscription_plan_assignments (
    id                UUID        NOT NULL DEFAULT gen_random_uuid(),
    subscription_id   UUID        NOT NULL,
    plan_version_id   UUID        NOT NULL,
    seat_quantity     INTEGER     NOT NULL,
    effective_from    TIMESTAMPTZ NOT NULL,
    effective_to      TIMESTAMPTZ,
    assignment_reason VARCHAR(40) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_subscription_plan_assignments PRIMARY KEY (id),
    CONSTRAINT fk_sub_assignments_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_assignments_plan_version FOREIGN KEY (plan_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_sub_assignments_seat_nonneg CHECK (seat_quantity >= 0),
    CONSTRAINT chk_sub_assignments_window CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    )
);

CREATE INDEX IF NOT EXISTS idx_sub_plan_assignments_sub_from
    ON subscription_plan_assignments (subscription_id, effective_from ASC);

-- ============================================================================
-- 3. Scheduled Plan Changes (Period-End Upgrades & Downgrades)
-- ============================================================================
CREATE TABLE IF NOT EXISTS subscription_scheduled_changes (
    id                      UUID         NOT NULL DEFAULT gen_random_uuid(),
    subscription_id         UUID         NOT NULL,
    current_plan_version_id UUID         NOT NULL,
    target_plan_version_id  UUID         NOT NULL,
    target_seat_quantity    INTEGER      NOT NULL,
    change_direction        VARCHAR(24)  NOT NULL,
    effective_at            TIMESTAMPTZ  NOT NULL,
    status                  VARCHAR(20)  NOT NULL,
    idempotency_key         VARCHAR(150),
    applied_at              TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_subscription_scheduled_changes PRIMARY KEY (id),
    CONSTRAINT fk_sub_sched_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_sched_current_pv FOREIGN KEY (current_plan_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_sched_target_pv FOREIGN KEY (target_plan_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_sub_sched_seat_nonneg CHECK (target_seat_quantity >= 0),
    CONSTRAINT chk_sub_sched_direction CHECK (change_direction IN ('UPGRADE', 'DOWNGRADE', 'PLAN_CHANGE')),
    CONSTRAINT chk_sub_sched_status CHECK (status IN ('PENDING', 'APPLIED', 'CANCELLED', 'SUPERSEDED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_scheduled_one_pending
    ON subscription_scheduled_changes (subscription_id)
    WHERE status = 'PENDING';

CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_scheduled_idempotency
    ON subscription_scheduled_changes (subscription_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sub_scheduled_due
    ON subscription_scheduled_changes (effective_at ASC)
    WHERE status = 'PENDING';

-- ============================================================================
-- 4. Explicit Proration Audit Records (NUMERIC(19,8) factor / NUMERIC(19,4) Money)
-- ============================================================================
CREATE TABLE IF NOT EXISTS subscription_proration_records (
    id                             UUID           NOT NULL DEFAULT gen_random_uuid(),
    subscription_id                UUID           NOT NULL,
    previous_plan_version_id       UUID           NOT NULL,
    new_plan_version_id            UUID           NOT NULL,
    previous_seat_quantity         INTEGER        NOT NULL,
    new_seat_quantity              INTEGER        NOT NULL,
    period_start                   TIMESTAMPTZ    NOT NULL,
    period_end                     TIMESTAMPTZ    NOT NULL,
    effective_at                   TIMESTAMPTZ    NOT NULL,
    total_period_seconds           BIGINT         NOT NULL,
    remaining_seconds              BIGINT         NOT NULL,
    proration_factor               NUMERIC(19, 8) NOT NULL,
    previous_plan_recurring_amount NUMERIC(19, 4) NOT NULL,
    new_plan_recurring_amount      NUMERIC(19, 4) NOT NULL,
    credit_amount                  NUMERIC(19, 4) NOT NULL,
    debit_amount                   NUMERIC(19, 4) NOT NULL,
    net_proration_amount           NUMERIC(19, 4) NOT NULL,
    currency_code                  VARCHAR(3)     NOT NULL,
    non_proratable_usage_charges   JSONB          NOT NULL DEFAULT '[]'::jsonb,
    calculation_policy             VARCHAR(40)    NOT NULL DEFAULT 'EXACT_SECONDS_V1',
    idempotency_key                VARCHAR(150),
    created_at                     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_subscription_proration_records PRIMARY KEY (id),
    CONSTRAINT fk_sub_proration_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_proration_prev_pv FOREIGN KEY (previous_plan_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_sub_proration_new_pv FOREIGN KEY (new_plan_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_sub_proration_period CHECK (period_end > period_start),
    CONSTRAINT chk_sub_proration_duration CHECK (
        total_period_seconds > 0 AND remaining_seconds >= 0 AND remaining_seconds <= total_period_seconds
    ),
    CONSTRAINT chk_sub_proration_nonneg CHECK (
        previous_plan_recurring_amount >= 0
        AND new_plan_recurring_amount >= 0
        AND credit_amount >= 0
        AND debit_amount >= 0
    ),
    CONSTRAINT chk_sub_proration_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_proration_idempotency
    ON subscription_proration_records (subscription_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sub_proration_sub_created
    ON subscription_proration_records (subscription_id, created_at DESC);

-- ============================================================================
-- 5. Immutable Subscription State-Transition Audit Trail
-- ============================================================================
CREATE TABLE IF NOT EXISTS subscription_state_transitions (
    id              UUID         NOT NULL DEFAULT gen_random_uuid(),
    subscription_id UUID         NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)  NOT NULL,
    reason          VARCHAR(80)  NOT NULL,
    effective_at    TIMESTAMPTZ  NOT NULL,
    idempotency_key VARCHAR(150),
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_subscription_state_transitions PRIMARY KEY (id),
    CONSTRAINT fk_sub_transitions_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT chk_sub_transitions_to_status CHECK (
        to_status IN ('TRIALING', 'ACTIVE', 'PAUSED', 'PAST_DUE', 'CANCELLED', 'EXPIRED')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_sub_transition_idempotency
    ON subscription_state_transitions (subscription_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sub_transitions_sub_created
    ON subscription_state_transitions (subscription_id, created_at ASC);
