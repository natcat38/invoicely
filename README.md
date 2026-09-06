# Invoicely

[![CI](https://github.com/natcat38/invoicely/actions/workflows/ci.yml/badge.svg)](https://github.com/natcat38/invoicely/actions/workflows/ci.yml)

Invoicing for a small Singapore business with more than one person touching
money.

Staff draft invoices; only the owner approves, sends, and records payments —
a maker-checker split enforced by the API, not just the UI. Invoices carry
GST at Singapore's rate when the business is GST-registered, track payments
against a running balance, and move through a fixed lifecycle
(`DRAFT → PENDING_APPROVAL → SENT → OVERDUE → PAID`). The owner gets a
dashboard of what's outstanding, overdue, and earned this month.

## Status

**Phase 1 (the API) is complete** — Tasks 1–6 of the
[Tech Scope](docs/Invoice_Tech_Scope.md) (domain model, CRUD, JWT auth with
roles, the full lifecycle and payments, tests, CI) followed by a full-repo
audit and hardening pass whose reports are kept in
[`reports/phase1-audit/`](reports/phase1-audit/).

**Phase 2 (the React UI) is complete.** `web/` is a Vite + TypeScript + React
app using Tailwind 4 and shadcn/ui: registration and sign-in, the forced
password change, clients, the invoice list, the invoice builder with its live
paper-document preview, maker-checker approvals, payments, the owner
dashboard, Team and Settings.

## Stack

Java 25 · Spring Boot 4.1 · Spring Security (JWT via OAuth2 Resource Server)
· Spring Data JPA · PostgreSQL 16 · Flyway · Testcontainers ·
springdoc-openapi · Docker Compose · GitHub Actions.

## Running it locally

Needs a JDK 25 on `JAVA_HOME` and Docker (for Postgres, and for
Testcontainers when running tests). Maven itself comes from the wrapper.

The commands below are PowerShell — run them one line at a time, no `&&`:

```powershell
docker compose up -d
.\mvnw spring-boot:run
```

That starts Postgres on `localhost:5432` and the app on
`http://localhost:8080`. The app generates a random JWT signing key at
startup unless `INVOICELY_SECURITY_SECRET` is set, so a restart invalidates
every issued token — fine for local use, not for anything meant to stay up.

To run the tests instead (spins up its own throwaway Postgres, no
`docker compose` needed first):

```powershell
.\mvnw verify
```

### The UI

Needs Node 20+. In a second terminal, with the API already running:

```powershell
cd web
npm install
npm run dev
```

That serves the app on `http://localhost:5173`, which is the origin the API
allows by default. Point it somewhere else by setting `VITE_API_BASE_URL`
(see `web/.env.example`) — and add that origin to
`invoicely.security.allowed-origins` on the API, or the browser will block
every call.

`npm run build` typechecks and bundles; `npm run lint` runs oxlint. CI runs
both.

> **Migrations note.** If the app fails to start with a Flyway *checksum
> mismatch for version 1*, your local database predates the real schema
> (`V1` was still a placeholder until Task 2). Local Postgres is disposable,
> so the fix is to let Flyway start over:
> `docker compose exec postgres psql -U invoicely -d invoicely -c "drop table flyway_schema_history;"`

## API tour

Interactive docs, once the app is running: `http://localhost:8080/swagger-ui.html`.

| Area | Method & path | Who |
|---|---|---|
| Auth | `POST /auth/register` | anyone (creates the business + owner) |
| Auth | `POST /auth/login` | anyone |
| Auth | `POST /auth/change-password` | any authenticated user |
| Auth | `GET /auth/me` | any authenticated user (session restore, and the business GST setting the builder needs) |
| Clients | `POST /clients`, `GET /clients` (`archived`, `q`, `page`, `size`), `GET /clients/{id}`, `PUT /clients/{id}`, `DELETE /clients/{id}` | any role |
| Invoices | `POST /invoices`, `GET /invoices` (`status`, `clientId`, `page`, `size`; defaults to a "needs attention" sort), `GET /invoices/{id}`, `PUT /invoices/{id}`, `DELETE /invoices/{id}` | any role |
| Lifecycle | `POST /invoices/{id}/submit` | any role (staff's half of maker-checker) |
| Lifecycle | `POST /invoices/{id}/send` | owner only |
| Lifecycle | `POST /invoices/{id}/reject` | owner only |
| Payments | `POST /invoices/{id}/payments`, `GET /invoices/{id}/payments` | owner only |
| Team | `GET /team`, `POST /team`, `PATCH /team/{id}` | owner only |
| Dashboard | `GET /dashboard` | owner only |
| Settings | `GET /settings`, `PUT /settings` | owner only (business name, address and UEN for the invoice letterhead, GST registration and rate, payment terms) |
| Ping | `GET /ping` | anyone, no auth (health check for a load balancer) |

`GET /v3/api-docs` and the Swagger UI paths are also open without a token,
since the documentation itself carries no data.

The two open endpoints are throttled: ten failed attempts from one IP address
in fifteen minutes answers `429` with a `Retry-After` header until the window
rolls over. Only failures count, so logging in successfully clears the tally.

## Design decisions

Each of these is a full ADR in [`docs/adr/`](docs/adr/) — worth reading if
you want to see the reasoning, not just the outcome.

- [0001 — business as ownership boundary](docs/adr/0001-business-as-ownership-boundary.md):
  every row is scoped by `business_id` from the JWT; a row in someone else's
  business returns 404, not 403, so a caller can't tell it exists.
- [0002 — JWT shape and storage](docs/adr/0002-jwt-shape-and-storage.md):
  short-lived HS256 tokens carrying only `sub`/`biz`/`role`, no refresh token,
  bearer header only (never a cookie, so CSRF protection can stay off).
- [0003 — temporary password flow](docs/adr/0003-temporary-password-flow.md):
  the server generates a new staff member's first password, returns it once,
  and forces a change before anything else works.
- [0004 — invoice numbering](docs/adr/0004-invoice-numbering.md):
  numbers come from `max(existing) + 1` under a per-business row lock, not a
  counter table — there's nothing to drift out of sync with the invoices
  themselves.
- [0005 — Java 25 / Spring Boot 4.1](docs/adr/0005-java-25-spring-boot-4.md):
  the scope docs originally pinned Java 21 / Boot 3, but Spring Initializr had
  already moved on by the time Task 1 started.
- [0006 — money and GST handling](docs/adr/0006-money-and-gst-handling.md):
  `BigDecimal`, rounded once at the subtotal rather than per line, with GST
  snapshotted at send so a later rate change can't rewrite a document a client
  already holds.
- [0007 — overdue-status dual mechanism](docs/adr/0007-overdue-status-dual-mechanism.md):
  a nightly bulk update persists `SENT → OVERDUE` so queries can filter on
  status directly; a pure read-time check covers the gap before the job runs.
- [0008 — timezone hardcoded to Singapore](docs/adr/0008-timezone-hardcoded-singapore.md):
  stated in code rather than via `TZ`, so a correctness rule can't silently
  depend on deployment configuration someone forgot to set.
- [0009 — owner/staff role model](docs/adr/0009-owner-staff-role-model.md):
  exactly two fixed roles, not a permissions table, with role checks (403) and
  status checks (409) enforced and tested completely separately.
- [0010 — session invalidation and login throttling](docs/adr/0010-session-invalidation-and-login-throttling.md):
  a password change invalidates every *other* session by comparing the token's
  `iat` against `password_changed_at` — no denylist, no session store — plus a
  per-IP throttle whose one-instance limits are stated rather than hidden.
- [0011 — invoice document data contract](docs/adr/0011-invoice-document-data-contract.md):
  the letterhead and bill-to render live so fixing a typo fixes every invoice;
  the GST rate stays the one snapshotted field, because it decides how much
  money is owed rather than how the page looks.

## A few things worth pointing out

- **Cross-business access is a 404, not a 403.** [`ADR-0001`](docs/adr/0001-business-as-ownership-boundary.md)
  treats "wrong business" and "wrong role" as different failures on purpose —
  a 403 confirms a row exists; a 404 doesn't. Every repository lookup goes
  through a `findByIdAndBusinessId`-style method; there's no plain `findById`
  in application code.
- **Role and state failures are kept apart and tested apart.** `InvoiceStatus.canTransitionTo`
  only knows about legal state transitions (→ 409); `@PreAuthorize` on each
  lifecycle endpoint only knows about roles (→ 403). `InvoiceStatusTest`
  checks the transition matrix with no Spring context at all, in
  milliseconds; `InvoiceLifecycleApiTest` checks who's allowed to drive each
  transition, over real HTTP.
- **The GST rate is snapshotted at send, not read live.** [`InvoiceTotals`](src/main/java/com/invoicely/domain/InvoiceTotals.java)
  uses the business's current GST setting for a draft, but once an invoice is
  sent it always uses `gst_rate_snapshot` — including when that snapshot is
  `null` because the business wasn't registered yet. A later rate change (or
  registering for GST after the fact) can never rewrite a document a client
  already holds.
- **The timezone is a constant in code, not a `TZ` environment variable.**
  [`BusinessCalendar`](src/main/java/com/invoicely/domain/BusinessCalendar.java)
  pins `Asia/Singapore` directly, because a container defaults to UTC and a
  correctness rule (what day is it, is this overdue yet) shouldn't depend on
  deployment configuration someone can forget to set.
- **Overdue is handled twice, deliberately.** A nightly `@Scheduled` job in
  [`OverdueInvoices`](src/main/java/com/invoicely/domain/OverdueInvoices.java)
  bulk-flips `SENT` invoices past their due date so list and dashboard
  queries can filter on `status = OVERDUE` directly. A second, pure method
  (`asOf`) computes the same answer for a single invoice on read, covering
  the gap between midnight and the job actually running.
- **Invoice numbering serialises on a row lock, not a counter table.**
  [`InvoiceNumbering`](src/main/java/com/invoicely/domain/InvoiceNumbering.java)
  takes `max(number) + 1` for the business and year, under a
  `SELECT ... FOR UPDATE` on the business row (borrowed anyway for payment
  terms and GST settings). It depends on PostgreSQL's default READ COMMITTED
  isolation — the ADR explains why raising it would silently break this.
- **Failures on identity are told apart by problem type, not just by status.**
  A deactivated account is a `403` (the token is genuine; the authorisation
  isn't), a token issued before its owner's last password change is a `401`,
  and an unreplaced temporary password is a different `403` again. Each
  carries its own `type` in the Problem Details body, so a UI can decide
  whether to clear the token or show an inline message without pattern-matching
  on prose. See [`AccountStateFilter`](src/main/java/com/invoicely/security/AccountStateFilter.java).
- **The login throttle is honest about being in-memory.** It is a
  `ConcurrentHashMap` in one process, so two instances would each allow ten
  attempts and a restart forgives everyone. That limit is written into
  [`ADR-0010`](docs/adr/0010-session-invalidation-and-login-throttling.md) and
  the class comment rather than left for someone to discover — the upgrade
  path is a shared store behind the same class. **For production** this and a
  managed signing secret are the two things to change first.

## Testing

`.\mvnw verify` runs the full suite against a real PostgreSQL via
Testcontainers — not an in-memory substitute — so schema constraints, numeric
precision, and case-insensitive unique indexes are exercised for real. It
covers: the money and GST math and the status-transition matrix as pure unit
tests; each controller end to end over MockMvc with real JWTs; the
maker-checker round trip (draft → submit → reject → resubmit → approve & send
→ payments → paid); cross-business isolation; the role checks that return 403
for staff attempting owner-only actions; and the concurrency cases that only
a real database can prove — two payments racing to overpay one invoice, and
two invoices racing for the same number. CI (`.github/workflows/ci.yml`) runs
the same command on every push and pull request.

## Project layout

```
src/main/java/com/invoicely/
  domain/     Entities, InvoiceStatus, InvoiceTotals, InvoiceNumbering,
              OverdueInvoices, BusinessCalendar, repositories
  security/   JWT issuing/verification, account-state filter, config
  web/        Controllers, DTOs, services, exception handling
docs/
  Invoice_Product_Scope.md    behaviour, roles, lifecycle, GST rules
  Invoice_Tech_Scope.md       stack, schema, task breakdown
  Invoice_Design_Direction.md Phase 2 UI layers (not yet built)
  adr/                        the decision records linked above
knowledge/    OKF-validated domain concepts (lifecycle, money, roles)
```

Directory-level index: [FILE-MAP.md](FILE-MAP.md).
