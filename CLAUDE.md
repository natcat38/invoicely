# Invoicely — working agreement

- **Claude builds this project.** The owner (Natalie) made all product/design
  decisions and is learning Java/Spring separately; implementation, tests,
  docs, and commits are Claude's job.
- **Product/design decisions stay the owner's.** Anything not already settled
  in the scope docs gets asked, not assumed (options + trade-offs, owner picks).
- Source of truth, in order: `docs/Invoice_Product_Scope.md` (behaviour, copy,
  roles, status matrix) → `docs/Invoice_Tech_Scope.md` (stack, schema, tasks
  1–9, gotchas) → `docs/Invoice_Design_Direction.md` (Phase 2 UI layers).
- Build in the Tech Scope's vertical slices, in order. One branch + PR per
  slice; CI green before merge; squash-merge. After Task 1 adds `ci.yml`,
  re-run /protect-repo so `verify` gates main.
- Money is `BigDecimal`, SGD only. Ownership boundary is `business_id` from
  the JWT; cross-business → 404. State errors 409, role errors 403.
- One ADR in `docs/adr/` per non-obvious decision, written when decided.
- `knowledge/` must pass the OKF house validator (pre-commit + CI); update
  concepts in the same PR that changes their behaviour.
- Write code a junior could read — the owner studies this codebase to learn
  Java/Spring, so favor clarity over cleverness and comment the *why*.

## Build & tooling gotchas (learned 2026-09-06)

- **Every commit reaches `main` through a PR, including docs-only ones.** Never
  push to `main` directly, even though the ruleset's admin bypass allows it.
- **Wait for `docker info` to succeed before any Maven command that touches
  Testcontainers.** Starting the suite while Docker Desktop is still booting
  produces a dozen context-load failures that look like code errors.
- **Review subagent *comments* as strictly as their code, and verify each
  factual claim.** Agents on this repo have written confident, wrong
  explanations (that TanStack prefix invalidation "cannot reach" a key it does
  reach; a false justification for index keys). A wrong explanation is worse
  than none here, because the owner reads this code to learn.
- **Check whether an API field is a `LocalDate` or an `Instant` before
  formatting it.** `format.date()` takes a `LocalDate` and deliberately does no
  timezone conversion; `format.dateOfInstant()` takes an `Instant` and converts
  to the Singapore business day. Passing an `Instant` to `date()` renders
  "NaN Sep 2026".
- **The API and the UI deploy as two origins** (`docs/adr/0012-two-deployables.md`).
  `/invoices`, `/clients`, `/team`, `/settings` and `/dashboard` are each both an
  API endpoint and a UI route, so single-origin hosting requires moving the whole
  API under `/api` first — do not propose it without that.
- **In Docker builds, build from `maven:<version>-eclipse-temurin-25`, never
  `./mvnw`.** The `eclipse-temurin` images ship no curl, wget or unzip, so the
  wrapper falls back to a Java downloader it cannot unpack and fails with a
  misleading "your Maven distribution might be compromised".
- **On a Flyway "checksum mismatch for version 1", drop `flyway_schema_history`
  in the local dev database.** V1 was a placeholder until Task 2; local Postgres
  is disposable and this is the intended fix, not a reason to edit a migration.
