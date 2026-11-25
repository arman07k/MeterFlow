-- V6: Billing & Invoicing — Billing Runs, Tax Policies, Invoices, Line Provenance, Prepaid Credits, Credit Notes & Adjustments (FR5, FR6, ADR-005, ADR-014)
-- Sole DDL authority: Flyway (spring.jpa.hibernate.ddl-auto=validate)
-- Preserves V1 through V5 unchanged; compatible identically across Local Docker Compose, Testcontainers, and Supabase PostgreSQL.

-- ============================================================================
-- 0. Sequential Numbering Sequences
-- ============================================================================
CREATE SEQUENCE IF NOT EXISTS invoice_number_seq START WITH 100001 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS credit_note_number_seq START WITH 100001 INCREMENT BY 1;

-- ============================================================================
-- 1. Billing Runs (Batch & Customer-Scoped Execution Audit)
-- ============================================================================
CREATE TABLE IF NOT EXISTS billing_runs (
    id                     UUID          NOT NULL DEFAULT gen_random_uuid(),
    batch_job_execution_id BIGINT,
    run_type               VARCHAR(24)   NOT NULL,
    customer_id            UUID,
    cutoff_timestamp       TIMESTAMPTZ   NOT NULL,
    idempotency_key        VARCHAR(150),
    status                 VARCHAR(24)   NOT NULL,
    processed_count        INTEGER       NOT NULL DEFAULT 0,
    created_count          INTEGER       NOT NULL DEFAULT 0,
    skipped_count          INTEGER       NOT NULL DEFAULT 0,
    failed_count           INTEGER       NOT NULL DEFAULT 0,
    error_summary          VARCHAR(500),
    started_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    completed_at           TIMESTAMPTZ,
    CONSTRAINT pk_billing_runs PRIMARY KEY (id),
    CONSTRAINT fk_billing_runs_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT chk_billing_runs_type CHECK (run_type IN ('DRAFT_GENERATION', 'FINALIZATION')),
    CONSTRAINT chk_billing_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT chk_billing_runs_counts CHECK (
        processed_count >= 0 AND created_count >= 0 AND skipped_count >= 0 AND failed_count >= 0
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_runs_idempotency
    ON billing_runs (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_billing_runs_started_at
    ON billing_runs (started_at DESC);

-- ============================================================================
-- 2. Explicit Deterministic Tax Policies
-- ============================================================================
CREATE TABLE IF NOT EXISTS tax_policies (
    id           UUID           NOT NULL DEFAULT gen_random_uuid(),
    policy_code  VARCHAR(64)    NOT NULL,
    name         VARCHAR(160)   NOT NULL,
    customer_id  UUID,
    jurisdiction VARCHAR(80)    NOT NULL,
    tax_name     VARCHAR(100)   NOT NULL,
    rate_percent NUMERIC(7, 4)  NOT NULL,
    tax_exempt   BOOLEAN        NOT NULL DEFAULT FALSE,
    active       BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_tax_policies PRIMARY KEY (id),
    CONSTRAINT uq_tax_policies_code UNIQUE (policy_code),
    CONSTRAINT fk_tax_policies_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT chk_tax_policies_rate CHECK (rate_percent >= 0 AND rate_percent <= 100)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tax_policies_active_customer
    ON tax_policies (customer_id)
    WHERE customer_id IS NOT NULL AND active = TRUE;

CREATE UNIQUE INDEX IF NOT EXISTS uq_tax_policies_active_global_default
    ON tax_policies ((1))
    WHERE customer_id IS NULL AND active = TRUE;

-- ============================================================================
-- 3. Invoices (DRAFT -> Immutable FINALIZED + Sequential Numbering)
-- ============================================================================
CREATE TABLE IF NOT EXISTS invoices (
    id                          UUID            NOT NULL DEFAULT gen_random_uuid(),
    invoice_number              VARCHAR(50),
    customer_id                 UUID            NOT NULL,
    subscription_id             UUID            NOT NULL,
    billing_run_id              UUID,
    period_start                TIMESTAMPTZ     NOT NULL,
    period_end                  TIMESTAMPTZ     NOT NULL,
    billing_timezone            VARCHAR(64)     NOT NULL DEFAULT 'UTC',
    grace_expires_at            TIMESTAMPTZ     NOT NULL,
    currency_code               VARCHAR(3)      NOT NULL,
    subtotal_amount             NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    taxable_amount              NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    tax_amount                  NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    total_amount                NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    credits_applied_amount      NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    credit_notes_applied_amount NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    amount_due                  NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    payable_amount_due          NUMERIC(19, 2)  NOT NULL DEFAULT 0,
    status                      VARCHAR(24)     NOT NULL,
    finalized_at                TIMESTAMPTZ,
    due_at                      TIMESTAMPTZ,
    voided_at                   TIMESTAMPTZ,
    void_reason                 VARCHAR(255),
    idempotency_key             VARCHAR(150),
    version                     BIGINT          NOT NULL DEFAULT 0,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_invoices PRIMARY KEY (id),
    CONSTRAINT uq_invoices_number UNIQUE (invoice_number),
    CONSTRAINT fk_invoices_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoices_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_invoices_billing_run FOREIGN KEY (billing_run_id)
        REFERENCES billing_runs (id) ON DELETE SET NULL,
    CONSTRAINT chk_invoices_period CHECK (period_end > period_start),
    CONSTRAINT chk_invoices_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_invoices_status CHECK (
        status IN ('DRAFT', 'FINALIZED', 'PAID', 'PAST_DUE', 'WRITTEN_OFF', 'VOID')
    ),
    CONSTRAINT chk_invoices_nonneg_credits CHECK (
        tax_amount >= 0
        AND credits_applied_amount >= 0
        AND credit_notes_applied_amount >= 0
        AND amount_due >= 0
        AND payable_amount_due >= 0
    )
);

-- Zero duplicate active invoices per subscription billing period (FR5, QA2)
CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_sub_period_active
    ON invoices (subscription_id, period_start, period_end)
    WHERE status <> 'VOID';

CREATE UNIQUE INDEX IF NOT EXISTS uq_invoices_idempotency
    ON invoices (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_invoices_customer_created
    ON invoices (customer_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_invoices_draft_due_finalize
    ON invoices (grace_expires_at ASC)
    WHERE status = 'DRAFT';

-- ============================================================================
-- 4. Invoice Lines (Full Line-Level Provenance)
-- ============================================================================
CREATE TABLE IF NOT EXISTS invoice_lines (
    id                      UUID            NOT NULL DEFAULT gen_random_uuid(),
    invoice_id              UUID            NOT NULL,
    line_order              INTEGER         NOT NULL,
    line_type               VARCHAR(32)     NOT NULL,
    description             VARCHAR(255)    NOT NULL,
    subscription_id         UUID            NOT NULL,
    plan_version_id         UUID,
    charge_id               UUID,
    charge_code             VARCHAR(80),
    charge_type             VARCHAR(40),
    metric_id               UUID,
    window_start            TIMESTAMPTZ     NOT NULL,
    window_end              TIMESTAMPTZ     NOT NULL,
    seat_quantity           INTEGER,
    raw_quantity            NUMERIC(19, 6)  NOT NULL DEFAULT 0,
    free_allowance_quantity NUMERIC(19, 6)  NOT NULL DEFAULT 0,
    billable_quantity       NUMERIC(19, 6)  NOT NULL DEFAULT 0,
    unit_price              NUMERIC(19, 8),
    line_amount             NUMERIC(19, 4)  NOT NULL,
    taxable                 BOOLEAN         NOT NULL DEFAULT TRUE,
    currency_code           VARCHAR(3)      NOT NULL,
    source_reference_id     UUID,
    calculation_breakdown   JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_invoice_lines PRIMARY KEY (id),
    CONSTRAINT fk_invoice_lines_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE CASCADE,
    CONSTRAINT uq_invoice_lines_order UNIQUE (invoice_id, line_order),
    CONSTRAINT chk_invoice_lines_type CHECK (
        line_type IN (
            'RECURRING_CHARGE',
            'USAGE_CHARGE',
            'MINIMUM_COMMITMENT_TRUE_UP',
            'PRORATION_ADJUSTMENT',
            'LATE_USAGE_ADJUSTMENT',
            'MANUAL_ADJUSTMENT'
        )
    ),
    CONSTRAINT chk_invoice_lines_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE INDEX IF NOT EXISTS idx_invoice_lines_invoice_order
    ON invoice_lines (invoice_id, line_order ASC);

-- ============================================================================
-- 5. Invoice Tax Lines (Traceable Tax Breakdown)
-- ============================================================================
CREATE TABLE IF NOT EXISTS invoice_tax_lines (
    id             UUID            NOT NULL DEFAULT gen_random_uuid(),
    invoice_id     UUID            NOT NULL,
    tax_policy_id  UUID,
    tax_name       VARCHAR(100)    NOT NULL,
    jurisdiction   VARCHAR(80)     NOT NULL,
    taxable_amount NUMERIC(19, 4)  NOT NULL,
    rate_percent   NUMERIC(7, 4)   NOT NULL,
    tax_amount     NUMERIC(19, 4)  NOT NULL,
    currency_code  VARCHAR(3)      NOT NULL,
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_invoice_tax_lines PRIMARY KEY (id),
    CONSTRAINT fk_invoice_tax_lines_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE CASCADE,
    CONSTRAINT fk_invoice_tax_lines_policy FOREIGN KEY (tax_policy_id)
        REFERENCES tax_policies (id) ON DELETE RESTRICT,
    CONSTRAINT chk_invoice_tax_lines_nonneg CHECK (rate_percent >= 0 AND tax_amount >= 0),
    CONSTRAINT chk_invoice_tax_lines_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE INDEX IF NOT EXISTS idx_invoice_tax_lines_invoice
    ON invoice_tax_lines (invoice_id);

-- ============================================================================
-- 6. Prepaid Credit Grants & Immutable Credit Transactions (FR6)
-- ============================================================================
CREATE TABLE IF NOT EXISTS prepaid_credit_grants (
    id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    customer_id      UUID            NOT NULL,
    currency_code    VARCHAR(3)      NOT NULL,
    grant_type       VARCHAR(24)     NOT NULL,
    description      VARCHAR(255)    NOT NULL,
    original_amount  NUMERIC(19, 4)  NOT NULL,
    remaining_amount NUMERIC(19, 4)  NOT NULL,
    priority         INTEGER         NOT NULL DEFAULT 100,
    expires_at       TIMESTAMPTZ,
    idempotency_key  VARCHAR(150),
    version          BIGINT          NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_prepaid_credit_grants PRIMARY KEY (id),
    CONSTRAINT fk_credit_grants_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT chk_credit_grants_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_credit_grants_type CHECK (grant_type IN ('PREPAID', 'PROMOTIONAL')),
    CONSTRAINT chk_credit_grants_amounts CHECK (
        original_amount > 0 AND remaining_amount >= 0 AND remaining_amount <= original_amount
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_credit_grants_idempotency
    ON prepaid_credit_grants (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_credit_grants_drawdown
    ON prepaid_credit_grants (customer_id, currency_code, expires_at ASC NULLS LAST, priority ASC, created_at ASC)
    WHERE remaining_amount > 0;

CREATE TABLE IF NOT EXISTS credit_transactions (
    id               UUID            NOT NULL DEFAULT gen_random_uuid(),
    credit_grant_id  UUID            NOT NULL,
    customer_id      UUID            NOT NULL,
    invoice_id       UUID,
    transaction_type VARCHAR(24)     NOT NULL,
    amount           NUMERIC(19, 4)  NOT NULL,
    balance_after    NUMERIC(19, 4)  NOT NULL,
    currency_code    VARCHAR(3)      NOT NULL,
    reason           VARCHAR(255)    NOT NULL,
    idempotency_key  VARCHAR(150),
    created_at       TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_credit_transactions PRIMARY KEY (id),
    CONSTRAINT fk_credit_tx_grant FOREIGN KEY (credit_grant_id)
        REFERENCES prepaid_credit_grants (id) ON DELETE RESTRICT,
    CONSTRAINT fk_credit_tx_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_credit_tx_invoice FOREIGN KEY (invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT chk_credit_tx_type CHECK (transaction_type IN ('ISSUED', 'CONSUMED', 'REVERSED')),
    CONSTRAINT chk_credit_tx_amounts CHECK (amount > 0 AND balance_after >= 0),
    CONSTRAINT chk_credit_tx_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_credit_tx_idempotency
    ON credit_transactions (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_credit_tx_customer_created
    ON credit_transactions (customer_id, created_at DESC);

-- ============================================================================
-- 7. Immutable Credit Notes & Lines (FR6)
-- ============================================================================
CREATE TABLE IF NOT EXISTS credit_notes (
    id                        UUID            NOT NULL DEFAULT gen_random_uuid(),
    credit_note_number        VARCHAR(50)     NOT NULL,
    original_invoice_id       UUID            NOT NULL,
    customer_id               UUID            NOT NULL,
    currency_code             VARCHAR(3)      NOT NULL,
    credit_type               VARCHAR(24)     NOT NULL,
    reason                    VARCHAR(255)    NOT NULL,
    subtotal_credit           NUMERIC(19, 4)  NOT NULL,
    tax_credit                NUMERIC(19, 4)  NOT NULL,
    total_credit              NUMERIC(19, 4)  NOT NULL,
    applied_to_invoice_due    NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    refunded_to_credit_wallet NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    idempotency_key           VARCHAR(150),
    issued_at                 TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    created_at                TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_credit_notes PRIMARY KEY (id),
    CONSTRAINT uq_credit_notes_number UNIQUE (credit_note_number),
    CONSTRAINT fk_credit_notes_invoice FOREIGN KEY (original_invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_credit_notes_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT chk_credit_notes_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_credit_notes_type CHECK (credit_type IN ('PARTIAL', 'FULL')),
    CONSTRAINT chk_credit_notes_amounts CHECK (
        subtotal_credit >= 0
        AND tax_credit >= 0
        AND total_credit > 0
        AND applied_to_invoice_due >= 0
        AND refunded_to_credit_wallet >= 0
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_credit_notes_idempotency
    ON credit_notes (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_credit_notes_invoice
    ON credit_notes (original_invoice_id, issued_at DESC);

CREATE INDEX IF NOT EXISTS idx_credit_notes_customer
    ON credit_notes (customer_id, issued_at DESC);

CREATE TABLE IF NOT EXISTS credit_note_lines (
    id                       UUID            NOT NULL DEFAULT gen_random_uuid(),
    credit_note_id           UUID            NOT NULL,
    original_invoice_line_id UUID,
    description              VARCHAR(255)    NOT NULL,
    credit_amount            NUMERIC(19, 4)  NOT NULL,
    tax_amount               NUMERIC(19, 4)  NOT NULL DEFAULT 0,
    currency_code            VARCHAR(3)      NOT NULL,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_credit_note_lines PRIMARY KEY (id),
    CONSTRAINT fk_cn_lines_credit_note FOREIGN KEY (credit_note_id)
        REFERENCES credit_notes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_cn_lines_invoice_line FOREIGN KEY (original_invoice_line_id)
        REFERENCES invoice_lines (id) ON DELETE RESTRICT,
    CONSTRAINT chk_cn_lines_amounts CHECK (credit_amount > 0 AND tax_amount >= 0),
    CONSTRAINT chk_cn_lines_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE INDEX IF NOT EXISTS idx_credit_note_lines_cn
    ON credit_note_lines (credit_note_id);

-- ============================================================================
-- 8. Billing Adjustments (Late Usage & Explicit Corrections — FR1, FR6)
-- ============================================================================
CREATE TABLE IF NOT EXISTS billing_adjustments (
    id                    UUID            NOT NULL DEFAULT gen_random_uuid(),
    customer_id           UUID            NOT NULL,
    subscription_id       UUID            NOT NULL,
    original_invoice_id   UUID,
    applied_invoice_id    UUID,
    late_usage_record_id  UUID,
    metric_id             UUID,
    adjustment_type       VARCHAR(32)     NOT NULL,
    description           VARCHAR(255)    NOT NULL,
    quantity_delta        NUMERIC(19, 6)  NOT NULL DEFAULT 0,
    amount                NUMERIC(19, 4)  NOT NULL,
    currency_code         VARCHAR(3)      NOT NULL,
    status                VARCHAR(24)     NOT NULL,
    idempotency_key       VARCHAR(150),
    calculation_breakdown JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_billing_adjustments PRIMARY KEY (id),
    CONSTRAINT uq_billing_adjustments_late_record UNIQUE (late_usage_record_id),
    CONSTRAINT fk_billing_adj_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT fk_billing_adj_subscription FOREIGN KEY (subscription_id)
        REFERENCES subscriptions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_billing_adj_orig_invoice FOREIGN KEY (original_invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT fk_billing_adj_applied_invoice FOREIGN KEY (applied_invoice_id)
        REFERENCES invoices (id) ON DELETE RESTRICT,
    CONSTRAINT chk_billing_adj_type CHECK (adjustment_type IN ('LATE_USAGE', 'MANUAL_CREDIT', 'MANUAL_DEBIT')),
    CONSTRAINT chk_billing_adj_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_billing_adj_status CHECK (status IN ('PENDING', 'APPLIED_TO_DRAFT', 'BILLED', 'VOID'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billing_adjustments_idempotency
    ON billing_adjustments (customer_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_billing_adj_sub_status
    ON billing_adjustments (subscription_id, status, created_at ASC);

-- ============================================================================
-- 9. PostgreSQL Immutability Triggers for Finalized Financial Records (FR5, QA2)
-- ============================================================================
CREATE OR REPLACE FUNCTION guard_finalized_invoice_immutable()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Cannot delete immutable non-DRAFT invoice % (status=%)', OLD.id, OLD.status;
        END IF;
        RETURN OLD;
    END IF;

    IF OLD.status <> 'DRAFT' THEN
        IF NEW.invoice_number IS DISTINCT FROM OLD.invoice_number
           OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
           OR NEW.subscription_id IS DISTINCT FROM OLD.subscription_id
           OR NEW.period_start IS DISTINCT FROM OLD.period_start
           OR NEW.period_end IS DISTINCT FROM OLD.period_end
           OR NEW.currency_code IS DISTINCT FROM OLD.currency_code
           OR NEW.subtotal_amount IS DISTINCT FROM OLD.subtotal_amount
           OR NEW.taxable_amount IS DISTINCT FROM OLD.taxable_amount
           OR NEW.tax_amount IS DISTINCT FROM OLD.tax_amount
           OR NEW.total_amount IS DISTINCT FROM OLD.total_amount
           OR NEW.credits_applied_amount IS DISTINCT FROM OLD.credits_applied_amount
           OR NEW.finalized_at IS DISTINCT FROM OLD.finalized_at
        THEN
            RAISE EXCEPTION 'Cannot modify immutable finalized financial fields on invoice % (status=%)', OLD.id, OLD.status;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_finalized_invoice_immutable ON invoices;
CREATE TRIGGER trg_guard_finalized_invoice_immutable
    BEFORE UPDATE OR DELETE ON invoices
    FOR EACH ROW
    EXECUTE FUNCTION guard_finalized_invoice_immutable();

CREATE OR REPLACE FUNCTION guard_finalized_invoice_child_immutable()
RETURNS TRIGGER AS $$
DECLARE
    parent_status VARCHAR(24);
    target_invoice_id UUID;
BEGIN
    target_invoice_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.invoice_id ELSE NEW.invoice_id END;
    SELECT status INTO parent_status FROM invoices WHERE id = target_invoice_id;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'Cannot modify or delete child rows on immutable non-DRAFT invoice % (status=%)', target_invoice_id, parent_status;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_invoice_lines_immutable ON invoice_lines;
CREATE TRIGGER trg_guard_invoice_lines_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON invoice_lines
    FOR EACH ROW
    EXECUTE FUNCTION guard_finalized_invoice_child_immutable();

DROP TRIGGER IF EXISTS trg_guard_invoice_tax_lines_immutable ON invoice_tax_lines;
CREATE TRIGGER trg_guard_invoice_tax_lines_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON invoice_tax_lines
    FOR EACH ROW
    EXECUTE FUNCTION guard_finalized_invoice_child_immutable();
