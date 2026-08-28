# Invoicely — Tech Scope

> You write all code by hand; Claude assists with docs and commit messages only.
> Snippets here are shapes to aim at, not code to paste.

## 1. Overview

**Stack:** Java 21 · Spring Boot 3.x · Spring Security (OAuth2 Resource Server, JWT) · Spring Data JPA · PostgreSQL 16 · Flyway · Testcontainers · springdoc-openapi · Docker Compose · GitHub Actions. Phase 2: React + Vite + TypeScript, TanStack Query, plain CSS.

### Phase 1 — API
| Task | Area | Effort |
|------|------|--------|
| 1. Skeleton + Compose + Flyway baseline | infra | 0.5 wk |
| 2. Domain entities + migrations | domain | 0.5 wk |
| 3. Client & Invoice CRUD (DTO/validation/errors) | web | 1 wk |
| 4. Auth (JWT) + ownership | security | 1 wk |
| 5. Lifecycle, payments, overdue job, reports | domain | 1 wk |
| 6. Tests, CI, OpenAPI, README | quality | 1 wk |

Dependency: Task 4 before Task 5 (ownership checks weave through lifecycle endpoints). Task 3 can start with security stubbed permit-all.

### Phase 2 — UI
| Task | Area | Effort |
|------|------|--------|
| 7. Vite app, auth flow, API client | fe | 0.5 wk |
| 8. Clients + invoice list/detail + payments | fe | 1 wk |
| 9. Dashboard + states + design polish | fe | 0.5 wk |

## 2. Core Logic

**Money:** `BigDecimal(19,4)` in DB, scale 2 at API boundary, `RoundingMode.HALF_UP`. Never float/double.

**Totals (derived, never stored as editable input):**
```
lineTotal    = quantity × unitPrice
invoiceTotal = Σ lineTotal
balance      = invoiceTotal − Σ payments.amount
status → PAID when balance == 0
```

| Scenario | Total | Payments | Balance | Status |
|----------|-------|----------|---------|--------|
| 2×S$100 items, no payment | 200.00 | — | 200.00 | SENT |
| partial S$150 | 200.00 | 150.00 | 50.00 | SENT |
| second payment S$50 | 200.00 | 200.00 | 0.00 | PAID |
| attempt S$60 on 50 balance | — | — | — | 400, rejected |

**Status machine:** implement as an enum method (`InvoiceStatus.canTransitionTo(target)`), enforced in the service layer; illegal → custom exception → 409 Problem Details. ⚠️ The `SENT → OVERDUE` transition is done by a `@Scheduled` daily job *and* computed on read (an invoice past due renders as OVERDUE even if the job hasn't run) — the job persists it, the read view guarantees correctness.

**Invoice numbering:** per-user sequence `INV-<year>-<zero-padded seq>`. ⚠️ Naive `max+1` races under concurrency — use a DB unique constraint on `(user_id, number)` and retry, or a sequence table row locked `FOR UPDATE`. Either is fine; document the choice in an ADR.

## 3. Data Model (migration V1)

```
users     (id, email UNIQUE, password_hash, created_at)
clients   (id, user_id FK, name, email, address, archived bool)
invoices  (id, user_id FK, client_id FK, number, status, issue_date,
           due_date, created_at, UNIQUE(user_id, number))
line_items(id, invoice_id FK, description, quantity, unit_price, position)
payments  (id, invoice_id FK, amount, paid_at, note)
```

⚠️ `user_id` is denormalized onto `invoices` deliberately — every ownership check is one column, no join through `clients`. Note it in an ADR.

## 4. Task Notes

**Task 1 — Skeleton.** Initializr deps: web, data-jpa, postgresql, flyway, validation, security, oauth2-resource-server, actuator. `compose.yaml` with Postgres 16. `V1__baseline.sql` empty-ish; app boots green.

**Task 2 — Domain.** Entities + repositories only; map `@OneToMany` per Vlad Mihalcea (mappedBy, helper add/remove methods, `orphanRemoval` on line items). No controllers yet; prove with a `@DataJpaTest`.

**Task 3 — CRUD.** Records as request/response DTOs, MapStruct optional (hand-mapping fine at this size). Bean Validation on request records. `@RestControllerAdvice` + `spring.mvc.problemdetails.enabled=true`. Pagination on list endpoints from day one (`Pageable`), filtering invoices by status/client.

**Task 4 — Auth.** `POST /auth/register`, `POST /auth/login` issue HS256 JWTs via `JwtEncoder`; resource-server config validates them. BCrypt. Ownership: every service method takes the authenticated user id and queries `findByIdAndUserId` — ⚠️ return 404 (not 403) for other users' resources, so IDs don't leak existence.

**Task 5 — Lifecycle.** `POST /invoices/{id}/send`, `POST /invoices/{id}/payments`. Dashboard: one endpoint, aggregate JPQL/native queries (outstanding, overdue count, revenue by month). `@Scheduled(cron = "0 0 1 * * *")` overdue job.

**Task 6 — Quality.** Unit tests: status machine, balance math, numbering. Integration: Testcontainers Postgres, full auth → create → send → pay flow, ownership-isolation test (user B cannot see user A's invoice). CI: GitHub Actions, `./mvnw verify`. springdoc at `/swagger-ui.html`. README per house standard.

**Tasks 7–9 — UI.** Vite + React + TS. TanStack Query for server state (no Redux). JWT in memory + refresh-on-load via localStorage (accepted tradeoff, note in ADR). Screens: Login/Register, Clients, Invoices (list w/ status filter), Invoice detail (the showcase screen — see Design doc), Dashboard. Every screen has explicit loading / empty / error states with the copy from the Product Scope.

## Implementation Notes

- API is the enforcement layer; never trust UI-side validation.
- One ADR per non-obvious decision (denormalized user_id, numbering strategy, JWT storage). `docs/adr/`.
- Commit per vertical slice, not per file; each phase ends with CI green.
- Apply house repo standard on creation: OKF bundle + validator, branch protection via /protect-repo, CI gate.
- Deploy target picked in Phase 2, but keep the app 12-factor (config via env vars) from Task 1 so deployment is a non-event.
