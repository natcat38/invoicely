# Invoicely

Invoicing for a small Singapore business with more than one person touching
money.

Staff draft invoices; only the owner approves, sends, and records payments —
a maker-checker split enforced by the API, not just the UI. Invoices carry
GST at Singapore's rate when the business is GST-registered, track payments
against a running balance, and move through a fixed lifecycle
(`DRAFT → PENDING_APPROVAL → SENT → OVERDUE → PAID`). The owner gets a
dashboard of what's outstanding, overdue, and earned this month.

## Status

**Phase 1 (the API) is complete through Task 6** of the
[Tech Scope](docs/Invoice_Tech_Scope.md): domain model, CRUD, JWT auth with
roles, the full lifecycle and payments, tests, and CI.

**Phase 2 (the React UI) is not built.** Nothing in this repo renders a
screen. Tasks 7–9 in the Tech Scope cover it; until then, the API is used
through [Swagger UI](#api-tour) or a plain HTTP client.

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

## API tour

Interactive docs, once the app is running: `http://localhost:8080/swagger-ui.html`.

| Area | Method & path | Who |
|---|---|---|
| Auth | `POST /auth/register` | anyone (creates the business + owner) |
| Auth | `POST /auth/login` | anyone |
| Auth | `POST /auth/change-password` | any authenticated user |
| Clients | `POST /clients`, `GET /clients`, `GET /clients/{id}`, `PUT /clients/{id}`, `DELETE /clients/{id}` | any role |
| Invoices | `POST /invoices`, `GET /invoices`, `GET /invoices/{id}`, `PUT /invoices/{id}`, `DELETE /invoices/{id}` | any role |
| Lifecycle | `POST /invoices/{id}/submit` | any role (staff's half of maker-checker) |
| Lifecycle | `POST /invoices/{id}/send` | owner only |
| Lifecycle | `POST /invoices/{id}/reject` | owner only |
| Payments | `POST /invoices/{id}/payments`, `GET /invoices/{id}/payments` | owner only |
| Team | `GET /team`, `POST /team`, `PATCH /team/{id}` | owner only |
| Dashboard | `GET /dashboard` | owner only |
| Settings | `GET /settings`, `PUT /settings` | owner only (business name, GST registration and rate, payment terms) |
| Ping | `GET /ping` | anyone, no auth (health check for a load balancer) |

`GET /v3/api-docs` and the Swagger UI paths are also open without a token,
since the documentation itself carries no data.

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

## Testing

`.\mvnw verify` runs the full suite against a real PostgreSQL via
Testcontainers — not an in-memory substitute — so schema constraints, numeric
precision, and case-insensitive unique indexes are exercised for real. It
covers: the money and GST math and the status-transition matrix as pure unit
tests; each controller end to end over MockMvc with real JWTs; the
maker-checker round trip (draft → submit → reject → resubmit → approve & send
→ payments → paid); cross-business isolation; and the role checks that
return 403 for staff attempting owner-only actions. CI (`.github/workflows/ci.yml`)
runs the same command on every push and pull request.

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
  adr/                        the five decision records linked above
knowledge/    OKF-validated domain concepts (lifecycle, money, roles)
```

Directory-level index: [FILE-MAP.md](FILE-MAP.md).
