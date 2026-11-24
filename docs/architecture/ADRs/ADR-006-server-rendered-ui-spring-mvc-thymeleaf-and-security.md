# ADR-006: Server-Rendered Web Portal with Spring MVC, Thymeleaf, and Dual Spring Security Chains

| Field | Value |
| :--- | :--- |
| **ADR ID** | `ADR-006` |
| **Status** | `Accepted` |
| **Date** | `2026-11-25` |
| **Deciders** | MeterFlow Engineering Team |
| **Related Requirements** | `FR1`–`FR10`, `QA4`, `QA5`, `C6`, `C7`, `C9` |
| **Supersedes** | `None` |
| **Superseded By** | `None` |

---

## 1. Context and Problem Statement

MeterFlow requires browser consoles for Administrators (metrics, immutable plan versions, batch controls), Operators (subscriptions, draft/final invoices, credits/credit notes, invoice simulation, dunning & webhook replay), and Customers (live `<= 1m` estimates, spend budgets, printable invoices, simulated payments, webhook endpoints), alongside high-throughput JSON ingestion endpoints (`/api/v1/events`, `/api/v1/events/batch`).

Project rules mandate **Spring MVC + Thymeleaf + Spring Security** and **strictly prohibit a React or Next.js frontend** (`C7`).

---

## 2. Decision Drivers

- **Strict Stack Compliance (`C7`)**: Zero React, Next.js, or separate SPA frontend builds.
- **Strong Tenant & Role Security (`QA4`, `C6`)**: Enforce `BCrypt`, RBAC, CSRF protection on browser forms, and `0` cross-customer IDOR leaks (`SC-QA4-1`).
- **Stateless Ingestion Performance (`FR1`, `QA1`)**: Isolate `/api/v1/events/**` from HTTP session creation overhead.
- **Fast Automated Controller Testing (`QA5`, `C9`)**: Test views, security rules, and REST adapters via MockMvc.

---

## 3. Considered Options

### Option A: Server-Rendered Spring MVC + Thymeleaf + Dual Ordered Spring Security 7 `SecurityFilterChain` Beans
- **Pros**: Built-in Thymeleaf CSRF & `th:text` XSS protection; zero CORS surface; single Maven build; clean separation between stateless `/api/v1/**` ingestion and stateful browser sessions inside `com.meterflow.portal`.
- **Cons**: Uses server-side page/fragment rendering rather than client-side SPA routing.

### Option B: Separate React or Next.js SPA
- **Pros**: Rich client state management.
- **Cons**: **Explicitly prohibited** (`C7`).

### Option C: Single Shared Stateful Security Chain Across Browser and `/api/v1/events`
- **Pros**: Slightly fewer config lines.
- **Cons**: Allocates unneeded HTTP sessions under `100`-VU `k6` event ingestion bursts (`QA1`) and complicates API client CSRF handling.

---

## 4. Decision Outcome

**Chosen Option**: **Option A — Spring MVC + Thymeleaf Secured by Dual Spring Security 7 Filter Chains**.

1. **`@Order(1)` API Chain (`/api/v1/**`)**: `SessionCreationPolicy.STATELESS`, authenticated client credentials, Jakarta Bean Validation (`@Valid`), RFC 9457 `ProblemDetail` JSON errors.
2. **`@Order(2)` Portal Chain (`/**`)**: Form login, `BCryptPasswordEncoder` (`strength >= 10`), session fixation migration, mandatory CSRF tokens on all stateful POST/PUT/DELETE forms, and strict route guards (`/admin/**` -> `ADMIN`, `/operator/**` -> `ADMIN`/`OPERATOR`, `/customer/**` -> `CUSTOMER`).
3. **Principal-Derived Tenant Scoping**: Customer endpoints resolve `customerId` strictly via `IdentityQueryFacade` from the authenticated security principal (`SC-QA4-1`).

---

## 5. Consequences

### Positive Consequences
- Unified deployment, automatic XSS/CSRF protection, printable HTML/CSS invoices and credit notes (`FR5`, `FR6`), and zero prohibited SPA tooling (`C7`).

### Negative Consequences and Trade-Offs
- Interactive simulation (`FR10`) and webhook history (`FR9`) use clean server-rendered HTML tables/forms (`Post-Redirect-Get`).

### Risks and Mitigations
- **Risk**: Direct UUID parameter tampering on `/customer/invoices/{id}` or `/customer/webhooks/{id}`.
  - **Mitigation**: Query always filters `WHERE id = :id AND customer_id = :principalCustomerId`, verified by automated negative IDOR tests (`SC-QA4-1`).

---

## 6. Compliance and Verification

- Repository check confirms zero React/Next.js files (`C7`).
- MockMvc security matrix verifies RBAC, CSRF rejection on untokenized form POSTs, and cross-customer IDOR rejection (`QA4`, `C9`).

---

## 7. Related Documents

- [`SYSTEM_DESIGN.md`](../SYSTEM_DESIGN.md)
- [`MODULE_BOUNDARIES.md`](../MODULE_BOUNDARIES.md)
- [`QUALITY_ATTRIBUTE_PLAN.md`](../QUALITY_ATTRIBUTE_PLAN.md)
