# Invoicely — Tech Scope (v2)

> v2 (2026-08-28): businesses + roles (maker-checker RBAC), GST, and the
> PENDING_APPROVAL state, per Product Scope v2. You write all code by hand;
> snippets are shapes, not paste material.

## 1. Overview

**Stack:** Java 21 · Spring Boot 3.x · Spring Security (JWT via OAuth2 Resource
Server, role claims) · Spring Data JPA · PostgreSQL 16 · Flyway · Testcontainers
· springdoc-openapi · Docker Compose · GitHub Actions.
Phase 2: React + Vite + TS, TanStack Query, shadcn/ui-style components, CSS
variables per Design Direction.

### Phase 1 — API
| Task | Area | Effort |
|------|------|--------|
| 1. Skeleton + Compose + Flyway baseline | infra | 0.5 wk |
| 2. Domain entities + migrations (incl. business, roles, GST) | domain | 1 wk |
| 3. Client & Invoice CRUD (DTO/validation/errors) | web | 1 wk |
| 4. Auth: JWT w/ role+business claims, team mgmt, forced password change | security | 1.5 wk |
| 5. Lifecycle (submit/approve/reject/send), payments, GST math, overdue job, dashboard | domain | 1.5 wk |
| 6. Tests, CI, OpenAPI, README | quality | 1 wk |

Dependency: Task 4 before Task 5 (role checks pervade lifecycle endpoints).
Task 3 may run with permit-all security stub.

### Phase 2 — UI
| Task | Area | Effort |
|------|------|--------|
| 7. Vite app, design tokens, auth + forced-change flow, role-aware routing | fe | 1 wk |
| 8. Clients, invoice list, builder w/ live document preview, approval queue, payments | fe | 1.5 wk |
| 9. Owner dashboard, Team & Settings pages, states, polish | fe | 0.5 wk |

## 2. Core Logic

**Money:** `NUMERIC(19,4)` / `BigDecimal`, scale 2 at API boundary, `HALF_UP`.

```
lineTotal    = quantity × unitPrice
subtotal     = Σ lineTotal
gst          = business GST-registered ? subtotal × gstRateSnapshot : 0
invoiceTotal = subtotal + gst
balance      = invoiceTotal − Σ payments.amount   → PAID at exactly 0
```

| Scenario | Subtotal | GST 9% | Total | Paid | Balance | Status |
|---|---|---|---|---|---|---|
| 2 items, registered biz | 1,420.00 | 127.80 | 1,547.80 | — | 1,547.80 | SENT |
| partial payment | 1,420.00 | 127.80 | 1,547.80 | 500.00 | 1,047.80 | SENT/OVERDUE |
| full payment | 1,420.00 | 127.80 | 1,547.80 | 1,547.80 | 0.00 | PAID |
| unregistered biz | 1,420.00 | — | 1,420.00 | … | … | … |

⚠️ `gst_rate_snapshot` is written on the DRAFT/PENDING → SENT transition and
never updated afterwards. Before sending, GST displays from the live business
setting; after, only from the snapshot.

**Status machine:** enum `InvoiceStatus` with `canTransitionTo(target)`;
service layer additionally checks **role** per transition (Product Scope §4
matrix). Illegal state → 409; insufficient role → 403. ⚠️ Two failure axes —
test them separately.

**Numbering:** `INV-<year>-<seq>` per **business**. Unique constraint on
`(business_id, number)` + retry on collision (or a locked sequence row —
ADR the choice).

## 3. Data Model (V1 migration)

```
businesses (id, name, gst_registered bool, gst_rate NUMERIC(5,4), created_at)
users      (id, business_id FK, email UNIQUE, password_hash,
            role 'OWNER'|'STAFF', must_change_password bool, active bool, created_at)
clients    (id, business_id FK, name, email, address, archived bool)
invoices   (id, business_id FK, client_id FK, created_by FK->users,
            number, status, issue_date, due_date,
            gst_rate_snapshot NUMERIC(5,4) NULL, rejection_note text NULL,
            sent_at, sent_by FK->users NULL, created_at,
            UNIQUE(business_id, number))
line_items (id, invoice_id FK, description, quantity, unit_price, position)
payments   (id, invoice_id FK, amount, paid_at, note, recorded_by FK->users)
```

⚠️ Ownership boundary is the **business**, not the user: every query filters by
`business_id` from the JWT (`findByIdAndBusinessId`). Cross-business access →
404 (never 403 — don't leak existence). `created_by`/`sent_by` are audit
attribution, not access control.

## 4. Task Notes

**Task 1 — Skeleton.** As before: Initializr (web, data-jpa, postgresql,
flyway, validation, security, oauth2-resource-server, actuator), compose.yaml
Postgres 16, boots green. Add `ci.yml` (`./mvnw verify`); re-run /protect-repo
after first PR so `verify` gates main.

**Task 2 — Domain.** All six tables in V1. Entities + repos, relationships per
Vlad Mihalcea. `@DataJpaTest` proof. ADR-0001: business as ownership boundary.

**Task 3 — CRUD.** Records as DTOs, Bean Validation, Problem Details,
pagination + status/client filters. Client archive rule (no delete with
invoices).

**Task 4 — Auth & team.**
- `POST /auth/register` → business + OWNER (transactional, one screen)
- `POST /auth/login` → JWT with claims: `sub` (user id), `biz`, `role`
- `POST /auth/change-password`; ⚠️ `must_change_password=true` blocks every
  other endpoint (403 with a distinct problem type the UI redirects on)
- Owner-only `GET/POST/PATCH /team` (create staff w/ generated temp password —
  returned once in the response, never stored plain; deactivate staff)
- Method security: `@PreAuthorize("hasRole('OWNER')")` on owner endpoints
- ADR-0002: JWT shape & storage; ADR-0003: temp-password flow

**Task 5 — Lifecycle & money.**
- `POST /invoices/{id}/submit` (any role, DRAFT only)
- `POST /invoices/{id}/send` (owner; from DRAFT or PENDING_APPROVAL; writes
  `gst_rate_snapshot`, `sent_at`, `sent_by`)
- `POST /invoices/{id}/reject` (owner; PENDING_APPROVAL → DRAFT; note required)
- `POST /invoices/{id}/payments` (owner; balance rule; flips to PAID at 0)
- `@Scheduled` daily overdue job + computed-on-read status
- `GET /dashboard` (owner): outstanding, overdue count, revenue/month,
  pending-approval queue — aggregate queries
- ADR-0004: numbering strategy

**Task 6 — Quality.** Unit: status×role matrix, GST/balance math, numbering.
Integration (Testcontainers): register → add staff → staff drafts & submits →
owner rejects → staff resubmits → owner approves & sends → payments → PAID;
cross-business isolation test; staff-forbidden tests (send/payments/dashboard
→ 403). Swagger. README.

**Tasks 7–9 — UI.** Role-aware routing (staff never see Dashboard/Team/Settings
nav items — and the API enforces it anyway). Builder = split view with live
paper-document preview (Design Direction). Approval queue on owner dashboard.
Forced password-change interstitial. All states with Product Scope copy.

## Implementation Notes

- API is authoritative; UI mirrors. Role checks live server-side; hiding a
  button is UX, not security.
- ⚠️ Don't put `business_id` in request bodies — always derive from the JWT.
- ⚠️ Staff deactivation: `active=false` must fail token validation on next
  request (check flag in the auth filter/service, not just at login).
- One ADR per starred decision above; commit per vertical slice; knowledge/
  bundle updated when a concept changes.
