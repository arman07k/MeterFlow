# MeterFlow — Security Architecture & Controls Overview (`docs/SECURITY_OVERVIEW.md`)

## 1. Security Model Summary

MeterFlow separates machine-to-machine REST traffic from browser-rendered portal workflows using two isolated **Spring Security 7** `SecurityFilterChain` beans defined in [`SecurityConfiguration.java`](../src/main/java/com/meterflow/identity/internal/SecurityConfiguration.java) ([ADR-006](./architecture/ADRs/ADR-006-server-rendered-ui-spring-mvc-thymeleaf-and-security.md)).

| Surface | Path Matcher | Session Policy | Authentication | CSRF | Error Format |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **REST API** (`@Order(1)`) | `/api/v1/**` | `STATELESS` | HTTP Basic (`DatabaseUserDetailsService`) | Disabled (stateless API) | RFC 9457 `application/problem+json` with `correlationId` |
| **Web Portals & Docs** (`@Order(2)`) | All other routes | Stateful (`migrateSession` on login) | Form login (`/login`) + `HttpOnly`/`SameSite=Lax` session cookie | **Enabled** on all state-changing forms & logout | Server-rendered error pages / flash messages (`PortalExceptionHandler`) |

---

## 2. Implemented Security Controls

### 2.1 Authentication & Password Storage
- **Database-backed identities (`V2__identity_schema.sql`)**: `DatabaseUserDetailsService` loads users from PostgreSQL `users` with normalized lowercase email lookup and rejects `DISABLED` or `SUSPENDED` accounts.
- **Password hashing**: `BCryptPasswordEncoder` with configurable strength (`meterflow.security.bcrypt-strength`, default **`12`**, validated within `10..15`).
- **Zero hardcoded credentials**: No default admin passwords exist in source code or YAML profiles. First-boot admin provisioning uses `InitialAdminBootstrapRunner`, which only runs when `METERFLOW_BOOTSTRAP_ADMIN_ENABLED=true` and enforces a minimum 12-character password.

### 2.2 Authorization, RBAC & Multi-Tenant Isolation
- **Platform roles (`MeterFlowRoles`)**: `ADMIN`, `OPERATOR`, and `CUSTOMER`.
- **Route-level RBAC**:
  - `/api/v1/admin/**`, `/admin/**`, `/actuator/**` (excluding `/actuator/health/**` and `/actuator/info`): `ROLE_ADMIN` only.
  - `/v3/api-docs/**`, `/swagger-ui/**`, `/operator/**`, and staff `/api/v1/**` endpoints: `ROLE_ADMIN` or `ROLE_OPERATOR`.
  - `/customer/**` portal views: `ROLE_CUSTOMER` only.
  - `/api/v1/customer/{customerId}/**`: `ADMIN`, `OPERATOR`, or `CUSTOMER`, guarded at the service/controller boundary by `CustomerAccessGuard`.
- **Live tenant ownership enforcement (`CustomerAccessGuard`)**: Customer principals can only access resources belonging to a `customer_id` where they hold an `ACTIVE` row in `customer_memberships`. Cross-tenant IDOR attempts return `403 Forbidden`.
- **Privilege boundaries**:
  - Customers cannot initiate mock payments, modify dunning policies, create/publish plans, or run `FR10` simulations against `DRAFT` plan versions (`403 Forbidden`).
  - Customer Portal `/customer/payments` is strictly read-only.

### 2.3 Browser Defense-in-Depth & API Hardening
- **HTTP security headers** (applied on both filter chains):
  - `Content-Security-Policy: default-src 'self'; frame-ancestors 'none'` (all styles live in external `static/css/meterflow.css`; zero inline `<style>` or `<script>` blocks).
  - `X-Frame-Options: DENY`
  - `X-Content-Type-Options: nosniff`
  - `Referrer-Policy: strict-origin-when-cross-origin`
- **Session security**: `HttpOnly: true`, `SameSite: lax`, session fixation migration (`migrateSession()`), full session invalidation + `JSESSIONID` cookie deletion on logout.
- **Bounded API pagination**: List endpoints clamp `size` to `1..100` and return stable `PageResponse<T>` envelopes, preventing unbounded-page memory exhaustion.

### 2.4 Outbound Webhook Cryptography & SSRF Protection (`FR9`, `ADR-009`)
- **Encryption at rest (`WebhookSecretCipher`)**: Signing secrets (`whsec_<32-byte-CSPRNG>`) are encrypted with `AES/GCM/NoPadding` (`AES-256-GCM`, fresh 12-byte random IV and 128-bit auth tag per encryption). Plaintext secrets are disclosed strictly once on endpoint creation or secret rotation.
- **Dual-key HMAC-SHA256 rotation (`WebhookSignatureVerifier`)**: Payloads are signed as `HMAC-SHA256(secret, "<unix_epoch>.<raw_utf8_body>")` in `X-MeterFlow-Signature: t=<epoch>,v1=<active>[,v1=<previous>]`. Rotation keeps the previous secret active for a configurable grace window (default `24h`). Verification uses constant-time comparison (`MessageDigest.isEqual`) and rejects timestamps skewed by more than `300` seconds.
- **SSRF validation (`SsrfUrlValidator`)**: Blocks non-HTTPS URLs, embedded URI credentials, `localhost`/`.local`/`.internal`/`metadata.google.internal`/`169.254.169.254`, and DNS resolutions pointing to loopback, site-local/RFC-1918, link-local, multicast, CGNAT (`100.64.0.0/10`), benchmark (`198.18.0.0/15`), `0.0.0.0/8`, or IPv6 ULA (`fc00::/7`) ranges when `meterflow.webhooks.allow-local-targets=false`.

### 2.5 Database-Enforced Financial & Audit Immutability
Thirteen PostgreSQL `BEFORE UPDATE OR DELETE` (and child `INSERT`) triggers across `V3`, `V6`, `V7`, and `V8` reject tampering even if application logic is bypassed:
- **`V3` (`5` triggers)**: Locks `PUBLISHED`/`ARCHIVED` `plan_versions` and their `plan_charges`, `charge_tiers`, `free_allowances`, and `minimum_commitments`.
- **`V6` (`3` triggers)**: Locks `FINALIZED`/`PAID`/`VOID` `invoices`, `invoice_lines`, and `invoice_tax_lines`.
- **`V7` (`3` triggers)**: Locks completed `payment_transactions` and enforces append-only `payment_attempts` and `dunning_events`.
- **`V8` (`2` triggers)**: Enforces append-only `webhook_delivery_attempts` and `audit_logs` (recording actor, action, entity, and details for webhook replays and operational actions).

---

## 3. Known Security Limitations & Operational Caveats

To keep this evaluation honest and transparent, the following limitations exist in the current implementation:

1. **Local Profile SSRF Relaxation**: [`application-local.yml`](../src/main/resources/application-local.yml) sets `meterflow.webhooks.allow-local-targets: true` so developers can test webhooks against local receivers. In non-local deployments (`application.yml` default and `supabase` profile), this defaults to `false` and must remain `false` in production.
2. **Local Non-Production Webhook Key Fallback**: When `WEBHOOK_SECRET_ENCRYPTION_KEY` is blank and the `supabase` profile is **not** active, `WebhookSecretCipher` logs a warning and derives a deterministic local development AES-256 key. The `supabase` profile fails fast on startup if `WEBHOOK_SECRET_ENCRYPTION_KEY` is missing or not a valid Base64-encoded 32-byte key. Always configure a strong external 32-byte key outside local dev.
3. **Stateless HTTP Basic Authentication**: `/api/v1/**` uses HTTP Basic with per-request `BCrypt(12)` verification rather than scoped API keys or OAuth2/JWT tokens. This is secure over HTTPS/TLS but adds `~100 ms` CPU latency per HTTP request and lacks per-key scope revocation or built-in rate limiting.
4. **TLS Termination & Rate Limiting**: MeterFlow runs plain HTTP on `SERVER_PORT` inside its container; production deployments must sit behind a TLS-terminating reverse proxy with `Secure` session cookies and edge rate limiting.
5. **No DNS-Pinning Against Rebinding During Delivery**: `SsrfUrlValidator` validates resolved IPs at endpoint registration/update and before delivery, but does not pin the resolved IP directly onto the JDK `HttpClient` socket connection.
6. **SpringDoc Swagger UI Enabled by Default**: `/swagger-ui.html` and `/v3/api-docs` are restricted to `ADMIN`/`OPERATOR` roles, but should be disabled (`springdoc.swagger-ui.enabled=false`) in hardened production environments if interactive docs are undesired.
