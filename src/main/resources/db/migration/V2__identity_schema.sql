-- V2: Identity & Tenant Isolation Schema (customers, users, customer_memberships)
-- Sole DDL authority: Flyway (spring.jpa.hibernate.ddl-auto=validate)
-- Shared identically across Local Docker Compose PostgreSQL, Testcontainers, and Supabase PostgreSQL.

-- ============================================================================
-- 1. Customers (Tenant Accounts)
-- ============================================================================
CREATE TABLE IF NOT EXISTS customers (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    external_key  VARCHAR(100) NOT NULL,
    name          VARCHAR(200) NOT NULL,
    currency_code VARCHAR(3)   NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_customers PRIMARY KEY (id),
    CONSTRAINT uq_customers_external_key UNIQUE (external_key),
    CONSTRAINT chk_customers_external_key_nonblank CHECK (btrim(external_key) <> ''),
    CONSTRAINT chk_customers_name_nonblank CHECK (btrim(name) <> ''),
    CONSTRAINT chk_customers_currency CHECK (currency_code IN ('BDT', 'USD')),
    CONSTRAINT chk_customers_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE INDEX IF NOT EXISTS idx_customers_status
    ON customers (status);

-- ============================================================================
-- 2. Application Users (Database-Backed Authentication & Platform Roles)
-- ============================================================================
CREATE TABLE IF NOT EXISTS users (
    id            UUID         NOT NULL DEFAULT gen_random_uuid(),
    email         VARCHAR(255) NOT NULL,
    full_name     VARCHAR(200) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    platform_role VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT chk_users_email_normalized CHECK (email = lower(btrim(email)) AND btrim(email) <> ''),
    CONSTRAINT chk_users_full_name_nonblank CHECK (btrim(full_name) <> ''),
    CONSTRAINT chk_users_password_hash_length CHECK (length(password_hash) >= 60),
    CONSTRAINT chk_users_platform_role CHECK (platform_role IN ('ADMIN', 'OPERATOR', 'CUSTOMER')),
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DISABLED', 'SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_users_role_status
    ON users (platform_role, status);

-- ============================================================================
-- 3. Customer Memberships (User-to-Customer Tenant Authorization)
-- ============================================================================
CREATE TABLE IF NOT EXISTS customer_memberships (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id         UUID        NOT NULL,
    customer_id     UUID        NOT NULL,
    membership_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_customer_memberships PRIMARY KEY (id),
    CONSTRAINT fk_customer_memberships_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_customer_memberships_customer FOREIGN KEY (customer_id)
        REFERENCES customers (id) ON DELETE RESTRICT,
    CONSTRAINT uq_customer_memberships_user_customer UNIQUE (user_id, customer_id),
    CONSTRAINT chk_customer_memberships_role CHECK (membership_role IN ('OWNER', 'BILLING_ADMIN', 'MEMBER')),
    CONSTRAINT chk_customer_memberships_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE INDEX IF NOT EXISTS idx_customer_memberships_user_active
    ON customer_memberships (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_customer_memberships_customer_active
    ON customer_memberships (customer_id)
    WHERE status = 'ACTIVE';
