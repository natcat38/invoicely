# Invoicely — Product Scope

## 1. Background & Problem Statement

Freelancers and very small businesses invoice clients through spreadsheets or Word templates. There is no single place to see who owes what, which invoices are overdue, or how much was earned this month. Chasing payments depends on memory.

This project is also a portfolio piece: it must demonstrate production-shaped backend competence (Java 21, Spring Boot 3, Spring Security, PostgreSQL) with a small frontend, built and designed with intent — unlike the three earlier code-only repos.

## 2. Proposed Solution

A web app where a freelancer:

- Registers and logs in (each user sees only their own data)
- Manages **clients** (name, email, billing address)
- Creates **invoices** for a client with line items (description, qty, unit price); totals are computed, never typed
- Moves invoices through a strict lifecycle: `DRAFT → SENT → PAID`, with `SENT → OVERDUE` applied automatically when the due date passes, and `OVERDUE → PAID` on payment
- Records **payments** against an invoice (partial payments allowed; invoice becomes PAID when fully covered)
- Sees a **dashboard**: outstanding total, overdue count, revenue this month

Hard rules (enforced in the backend, mirrored in the UI):

- A SENT/PAID invoice's line items cannot be edited — only DRAFT invoices are editable
- Illegal status jumps (e.g. DRAFT → PAID) are rejected
- A payment cannot exceed the invoice's remaining balance
- All money values are exact decimals in one currency (SGD) — no floats, no multi-currency

Phases: **Phase 1 — API** (complete, tested, documented backend). **Phase 2 — UI** (minimal React frontend: login, client list, invoice list/detail, dashboard).

## 3. Scope of Work

### Phase 1 — API (the résumé core)
| Layer | Task | Effort |
|-------|------|--------|
| Backend | Project skeleton, Docker Postgres, Flyway baseline | 0.5 wk |
| Backend | Domain model + migrations (User, Client, Invoice, LineItem, Payment) | 0.5 wk |
| Backend | Client + Invoice CRUD with DTOs, validation, Problem Details errors | 1 wk |
| Backend | JWT auth + per-user ownership enforcement | 1 wk |
| Backend | Status lifecycle, payments, overdue job, dashboard/report queries | 1 wk |
| Backend | Tests (unit + Testcontainers), CI, Swagger, README | 1 wk |

### Phase 2 — UI (minimal, designed)
| Layer | Task | Effort |
|-------|------|--------|
| Frontend | React app: auth screens, client list | 0.5 wk |
| Frontend | Invoice list + invoice detail (the signature screen), payment entry | 1 wk |
| Frontend | Dashboard, empty/error/loading states, design polish | 0.5 wk |

**Total: ~7 weeks part-time.** Cut line: Phase 1 alone is a complete, presentable project.

## 4. User-Facing Behaviour

### 4.1 Auth
Register (email + password, min 8 chars) and log in. Errors are specific: `"An account with this email already exists."`, `"Email or password is incorrect."` Session expiry returns the user to login with `"Your session expired. Log in again to continue."`

### 4.2 Clients
List, create, edit. A client with invoices cannot be deleted — the delete action explains: `"This client has invoices. Archive it instead."` (archive hides it from pickers, keeps history).

### 4.3 Invoices
- Creating an invoice starts it in DRAFT with an auto-assigned number (`INV-2026-0001`, per user, sequential).
- DRAFT: line items editable, actions **Send** and **Delete**.
- SENT: read-only items, actions **Record payment**; due date visible; badge turns to OVERDUE automatically after due date.
- PAID: fully read-only, shows payment history.
- Attempting to edit a non-draft invoice shows: `"Sent invoices can't be edited. Create a credit note or a new invoice."` (credit notes are out of scope — the message names the real-world escape hatch.)

### 4.4 Payments
Payment form pre-fills the remaining balance. Overpayment is blocked inline: `"Amount exceeds the remaining balance (S$420.00)."` Full payment flips status to PAID and shows `"Invoice INV-2026-0001 marked as paid."`

### 4.5 Validation summary
Every rule is enforced twice: once in the UI (inline, before submit) and once in the API (authoritative — returns 400/409 Problem Details). The API is the source of truth; the UI is a convenience layer.

## 5. Decision Matrix — status transitions

| From \ To | DRAFT | SENT | OVERDUE | PAID |
|-----------|-------|------|---------|------|
| DRAFT | — | ✅ send | ❌ | ❌ |
| SENT | ❌ | — | ✅ auto (due date passed) | ✅ via full payment |
| OVERDUE | ❌ | ❌ | — | ✅ via full payment |
| PAID | ❌ | ❌ | ❌ | — |

All ❌ transitions return HTTP 409 with a Problem Details body naming the current status.

## 6. Out of Scope

- Multi-currency, tax/GST computation, credit notes, recurring invoices
- Sending real emails (Send = status change only)
- PDF generation (documented as a plausible v2)
- Teams/roles — strictly one user, their data
- Mobile app

## 7. Rollout Plan

Solo portfolio project; "rollout" = public GitHub repo meeting the house standard (OKF bundle, branch protection, CI green). Phase 1 target: ~5 weeks from repo creation. Phase 2: +2 weeks. Deployed demo (Railway/Render/Fly.io) at the end of Phase 2 so recruiters can click, not clone.
