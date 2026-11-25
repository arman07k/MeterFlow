-- V3: Catalogue Schema (Billable Metrics, Filters, Pricing Plans, Immutable Plan Versions,
-- Charges, Tiers, Free Allowances, Minimum Commitments & Immutability Triggers)
-- Sole DDL authority: Flyway (spring.jpa.hibernate.ddl-auto=validate)
-- Compatible identically across Local Docker Compose PostgreSQL, Testcontainers, and Supabase PostgreSQL.

-- ============================================================================
-- 1. Billable Metrics & Configurable Property Filters (FR2)
-- ============================================================================
CREATE TABLE IF NOT EXISTS billable_metrics (
    id               UUID         NOT NULL DEFAULT gen_random_uuid(),
    metric_key       VARCHAR(80)  NOT NULL,
    name             VARCHAR(160) NOT NULL,
    description      TEXT,
    aggregation_type VARCHAR(20)  NOT NULL,
    target_property  VARCHAR(120),
    customer_id      UUID,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    version          BIGINT       NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_billable_metrics PRIMARY KEY (id),
    CONSTRAINT fk_billable_metrics_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT chk_billable_metrics_key_nonblank CHECK (btrim(metric_key) <> ''),
    CONSTRAINT chk_billable_metrics_name_nonblank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_billable_metrics_agg_type CHECK (
        aggregation_type IN ('SUM', 'COUNT', 'MAXIMUM', 'UNIQUE_COUNT', 'LATEST_VALUE')
    ),
    CONSTRAINT chk_billable_metrics_target_prop CHECK (
        aggregation_type = 'COUNT' OR (target_property IS NOT NULL AND btrim(target_property) <> '')
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_billable_metrics_global_key
    ON billable_metrics (metric_key)
    WHERE customer_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_billable_metrics_customer_key
    ON billable_metrics (customer_id, metric_key)
    WHERE customer_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_billable_metrics_customer_active
    ON billable_metrics (customer_id, active);

CREATE TABLE IF NOT EXISTS metric_filters (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    metric_id     UUID         NOT NULL,
    filter_order  INTEGER      NOT NULL,
    property_key  VARCHAR(120) NOT NULL,
    operator      VARCHAR(20)  NOT NULL,
    filter_values JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_metric_filters PRIMARY KEY (id),
    CONSTRAINT fk_metric_filters_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE CASCADE,
    CONSTRAINT uq_metric_filters_order UNIQUE (metric_id, filter_order),
    CONSTRAINT chk_metric_filters_order_positive CHECK (filter_order >= 1),
    CONSTRAINT chk_metric_filters_prop_nonblank CHECK (btrim(property_key) <> ''),
    CONSTRAINT chk_metric_filters_operator CHECK (
        operator IN ('EQUALS', 'NOT_EQUALS', 'IN', 'NOT_IN', 'EXISTS')
    )
);

CREATE INDEX IF NOT EXISTS idx_metric_filters_metric
    ON metric_filters (metric_id, filter_order);

-- ============================================================================
-- 2. Pricing Plans & Immutable Plan Versions (FR3, FR10)
-- ============================================================================
CREATE TABLE IF NOT EXISTS pricing_plans (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    plan_code     VARCHAR(80)  NOT NULL,
    name          VARCHAR(160) NOT NULL,
    description   TEXT,
    currency_code VARCHAR(3)   NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_pricing_plans PRIMARY KEY (id),
    CONSTRAINT uq_pricing_plans_code UNIQUE (plan_code),
    CONSTRAINT chk_pricing_plans_code_nonblank CHECK (btrim(plan_code) <> ''),
    CONSTRAINT chk_pricing_plans_name_nonblank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_pricing_plans_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE INDEX IF NOT EXISTS idx_pricing_plans_active
    ON pricing_plans (active);

CREATE TABLE IF NOT EXISTS plan_versions (
    id                     UUID        NOT NULL DEFAULT gen_random_uuid(),
    plan_id                UUID        NOT NULL,
    version_number         INTEGER     NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    effective_from         TIMESTAMPTZ,
    published_at           TIMESTAMPTZ,
    cloned_from_version_id UUID,
    version                BIGINT      NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_plan_versions PRIMARY KEY (id),
    CONSTRAINT fk_plan_versions_plan FOREIGN KEY (plan_id)
        REFERENCES pricing_plans (id) ON DELETE RESTRICT,
    CONSTRAINT fk_plan_versions_cloned_from FOREIGN KEY (cloned_from_version_id)
        REFERENCES plan_versions (id) ON DELETE RESTRICT,
    CONSTRAINT uq_plan_versions_plan_num UNIQUE (plan_id, version_number),
    CONSTRAINT chk_plan_versions_number_positive CHECK (version_number >= 1),
    CONSTRAINT chk_plan_versions_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_plan_versions_published_at CHECK (
        status = 'DRAFT' OR published_at IS NOT NULL
    )
);

CREATE INDEX IF NOT EXISTS idx_plan_versions_plan_status
    ON plan_versions (plan_id, status, version_number DESC);

-- ============================================================================
-- 3. Plan Charges & Pricing Tiers (FR3, ADR-010, ADR-011)
-- ============================================================================
CREATE TABLE IF NOT EXISTS plan_charges (
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    plan_version_id UUID           NOT NULL,
    display_order   INTEGER        NOT NULL,
    charge_code     VARCHAR(80)    NOT NULL,
    description     VARCHAR(255),
    charge_type     VARCHAR(40)    NOT NULL,
    metric_id       UUID,
    flat_amount     NUMERIC(19, 4),
    unit_price      NUMERIC(19, 8),
    package_size    NUMERIC(19, 4),
    package_price   NUMERIC(19, 4),
    charge_config   JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_plan_charges PRIMARY KEY (id),
    CONSTRAINT fk_plan_charges_version FOREIGN KEY (plan_version_id)
        REFERENCES plan_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_plan_charges_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE RESTRICT,
    CONSTRAINT uq_plan_charges_code UNIQUE (plan_version_id, charge_code),
    CONSTRAINT uq_plan_charges_order UNIQUE (plan_version_id, display_order),
    CONSTRAINT chk_plan_charges_order_positive CHECK (display_order >= 1),
    CONSTRAINT chk_plan_charges_code_nonblank CHECK (btrim(charge_code) <> ''),
    CONSTRAINT chk_plan_charges_type_nonblank CHECK (btrim(charge_type) <> ''),
    CONSTRAINT chk_plan_charges_flat_nonneg CHECK (flat_amount IS NULL OR flat_amount >= 0),
    CONSTRAINT chk_plan_charges_unit_nonneg CHECK (unit_price IS NULL OR unit_price >= 0),
    CONSTRAINT chk_plan_charges_pkg_size_pos CHECK (package_size IS NULL OR package_size > 0),
    CONSTRAINT chk_plan_charges_pkg_price_nonneg CHECK (package_price IS NULL OR package_price >= 0)
);

CREATE INDEX IF NOT EXISTS idx_plan_charges_version
    ON plan_charges (plan_version_id, display_order);

CREATE TABLE IF NOT EXISTS charge_tiers (
    id             UUID           NOT NULL DEFAULT gen_random_uuid(),
    plan_charge_id UUID           NOT NULL,
    tier_order     INTEGER        NOT NULL,
    lower_bound    NUMERIC(19, 4) NOT NULL,
    upper_bound    NUMERIC(19, 4),
    unit_price     NUMERIC(19, 8) NOT NULL,
    flat_fee       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    description    VARCHAR(255),
    CONSTRAINT pk_charge_tiers PRIMARY KEY (id),
    CONSTRAINT fk_charge_tiers_charge FOREIGN KEY (plan_charge_id)
        REFERENCES plan_charges (id) ON DELETE CASCADE,
    CONSTRAINT uq_charge_tiers_order UNIQUE (plan_charge_id, tier_order),
    CONSTRAINT chk_charge_tiers_order_positive CHECK (tier_order >= 1),
    CONSTRAINT chk_charge_tiers_lower_nonneg CHECK (lower_bound >= 0),
    CONSTRAINT chk_charge_tiers_bounds CHECK (upper_bound IS NULL OR lower_bound < upper_bound),
    CONSTRAINT chk_charge_tiers_unit_nonneg CHECK (unit_price >= 0),
    CONSTRAINT chk_charge_tiers_flat_nonneg CHECK (flat_fee >= 0)
);

CREATE INDEX IF NOT EXISTS idx_charge_tiers_charge
    ON charge_tiers (plan_charge_id, tier_order);

-- ============================================================================
-- 4. Free Allowances & Minimum Commitments (FR3, ADR-011)
-- ============================================================================
CREATE TABLE IF NOT EXISTS free_allowances (
    id               UUID           NOT NULL DEFAULT gen_random_uuid(),
    plan_version_id  UUID           NOT NULL,
    metric_id        UUID,
    target_charge_id UUID,
    free_quantity    NUMERIC(19, 4) NOT NULL,
    reset_period     VARCHAR(20)    NOT NULL DEFAULT 'BILLING_PERIOD',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_free_allowances PRIMARY KEY (id),
    CONSTRAINT fk_free_allowances_version FOREIGN KEY (plan_version_id)
        REFERENCES plan_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_free_allowances_metric FOREIGN KEY (metric_id)
        REFERENCES billable_metrics (id) ON DELETE RESTRICT,
    CONSTRAINT fk_free_allowances_charge FOREIGN KEY (target_charge_id)
        REFERENCES plan_charges (id) ON DELETE CASCADE,
    CONSTRAINT chk_free_allowances_target CHECK (metric_id IS NOT NULL OR target_charge_id IS NOT NULL),
    CONSTRAINT chk_free_allowances_qty_positive CHECK (free_quantity > 0),
    CONSTRAINT chk_free_allowances_reset_period CHECK (reset_period IN ('BILLING_PERIOD', 'MONTHLY', 'ANNUAL'))
);

CREATE INDEX IF NOT EXISTS idx_free_allowances_version
    ON free_allowances (plan_version_id);

CREATE TABLE IF NOT EXISTS minimum_commitments (
    id              UUID           NOT NULL DEFAULT gen_random_uuid(),
    plan_version_id UUID           NOT NULL,
    plan_charge_id  UUID,
    minimum_amount  NUMERIC(19, 4) NOT NULL,
    currency_code   VARCHAR(3)     NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_minimum_commitments PRIMARY KEY (id),
    CONSTRAINT fk_minimum_commitments_version FOREIGN KEY (plan_version_id)
        REFERENCES plan_versions (id) ON DELETE CASCADE,
    CONSTRAINT fk_minimum_commitments_charge FOREIGN KEY (plan_charge_id)
        REFERENCES plan_charges (id) ON DELETE CASCADE,
    CONSTRAINT chk_minimum_commitments_nonneg CHECK (minimum_amount >= 0),
    CONSTRAINT chk_minimum_commitments_currency CHECK (currency_code IN ('BDT', 'USD'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_minimum_commitments_plan_level
    ON minimum_commitments (plan_version_id)
    WHERE plan_charge_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_minimum_commitments_charge_level
    ON minimum_commitments (plan_version_id, plan_charge_id)
    WHERE plan_charge_id IS NOT NULL;

-- ============================================================================
-- 5. PostgreSQL Immutability Enforcement Triggers for PUBLISHED Plan Versions (FR10)
-- ============================================================================
CREATE OR REPLACE FUNCTION meterflow_guard_published_plan_version()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status IN ('PUBLISHED', 'ARCHIVED') THEN
            RAISE EXCEPTION 'Cannot delete immutable % plan_version %', OLD.status, OLD.id
                USING ERRCODE = '22000';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.status = 'ARCHIVED' THEN
            RAISE EXCEPTION 'Cannot modify immutable ARCHIVED plan_version %', OLD.id
                USING ERRCODE = '22000';
        END IF;
        IF OLD.status = 'PUBLISHED' THEN
            IF NEW.status = 'ARCHIVED'
               AND NEW.plan_id = OLD.plan_id
               AND NEW.version_number = OLD.version_number
               AND NEW.effective_from IS NOT DISTINCT FROM OLD.effective_from
               AND NEW.published_at IS NOT DISTINCT FROM OLD.published_at
               AND NEW.cloned_from_version_id IS NOT DISTINCT FROM OLD.cloned_from_version_id THEN
                RETURN NEW;
            END IF;
            RAISE EXCEPTION 'Cannot modify immutable PUBLISHED plan_version %', OLD.id
                USING ERRCODE = '22000';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_published_plan_version ON plan_versions;
CREATE TRIGGER trg_guard_published_plan_version
    BEFORE UPDATE OR DELETE ON plan_versions
    FOR EACH ROW
    EXECUTE FUNCTION meterflow_guard_published_plan_version();

CREATE OR REPLACE FUNCTION meterflow_guard_published_plan_version_child()
RETURNS TRIGGER AS $$
DECLARE
    v_plan_version_id UUID;
    v_status          VARCHAR(20);
BEGIN
    IF TG_TABLE_NAME = 'charge_tiers' THEN
        SELECT pc.plan_version_id INTO v_plan_version_id
        FROM plan_charges pc
        WHERE pc.id = COALESCE(NEW.plan_charge_id, OLD.plan_charge_id);
    ELSE
        v_plan_version_id := COALESCE(NEW.plan_version_id, OLD.plan_version_id);
    END IF;

    SELECT pv.status INTO v_status
    FROM plan_versions pv
    WHERE pv.id = v_plan_version_id;

    IF v_status IN ('PUBLISHED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'Cannot mutate % belonging to immutable % plan_version %',
            TG_TABLE_NAME, v_status, v_plan_version_id
            USING ERRCODE = '22000';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_guard_plan_charges_immutable ON plan_charges;
CREATE TRIGGER trg_guard_plan_charges_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON plan_charges
    FOR EACH ROW
    EXECUTE FUNCTION meterflow_guard_published_plan_version_child();

DROP TRIGGER IF EXISTS trg_guard_charge_tiers_immutable ON charge_tiers;
CREATE TRIGGER trg_guard_charge_tiers_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON charge_tiers
    FOR EACH ROW
    EXECUTE FUNCTION meterflow_guard_published_plan_version_child();

DROP TRIGGER IF EXISTS trg_guard_free_allowances_immutable ON free_allowances;
CREATE TRIGGER trg_guard_free_allowances_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON free_allowances
    FOR EACH ROW
    EXECUTE FUNCTION meterflow_guard_published_plan_version_child();

DROP TRIGGER IF EXISTS trg_guard_minimum_commitments_immutable ON minimum_commitments;
CREATE TRIGGER trg_guard_minimum_commitments_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON minimum_commitments
    FOR EACH ROW
    EXECUTE FUNCTION meterflow_guard_published_plan_version_child();
