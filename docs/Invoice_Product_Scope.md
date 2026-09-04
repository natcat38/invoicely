# Invoicely — Product Scope (v2)

> v2 (2026-08-28): reshaped by the owner from freelancer-tool to small-business
> tool — multi-staff roles (maker-checker), GST, owner-created staff accounts.

## 1. Background & Problem Statement

Small businesses invoice clients through spreadsheets and Word templates. There
is no shared place for the team to see who owes what, which invoices are
overdue, or what was earned this month — and no control over which staff member
may actually send an invoice or touch the money numbers.

This is also a portfolio piece: production-shaped backend competence (Java 25,
Spring Boot 4, Spring Security with roles, PostgreSQL) plus a designed frontend.

## 2. Proposed Solution

A web app for one small business with multiple staff:

- **Registration creates the business and its OWNER account** in one step.
- The **owner adds STAFF accounts** directly (email + temporary password;
  staff must change it on first login). No invite emails.
- Anyone manages **clients**; **invoices** hold line items with computed totals
  and **GST** (if the business is GST-registered, rate snapshotted at send).
- **Maker-checker lifecycle**: staff build drafts and submit them;
  only the owner approves & sends, records payments, and sees revenue.
- `SENT → OVERDUE` happens automatically after the due date.
- **Owner-only dashboard**, four headline stats: outstanding total,
  overdue amount + count, revenue this month, awaiting-approval count
  (links into the queue). Staff land on the invoice list.
- Owner's **Team page**: per staff member — name, email, active status,
  invoices-created count, last-active date; Add and Deactivate actions.

Hard rules (enforced in the API, mirrored in the UI):

- Only DRAFT invoices are editable; only PENDING_APPROVAL can be approved/rejected
- Illegal status transitions rejected (409)
- A payment cannot exceed the invoice's remaining balance
- STAFF cannot send invoices, record payments, view dashboard/revenue, or manage team
- Money is exact decimal, single currency (SGD)

Phases: **Phase 1 — API**, **Phase 2 — UI** (SaaS app layer + paper document
layer per the Design Direction).

## 3. Roles & Permissions

| Capability | STAFF | OWNER |
|---|---|---|
| Manage clients | ✅ | ✅ |
| Create/edit DRAFT invoices | ✅ | ✅ |
| Submit for approval | ✅ | ✅ (or send directly) |
| Approve & send / reject | ❌ | ✅ |
| Record payments | ❌ | ✅ |
| Dashboard / revenue figures | ❌ | ✅ |
| Manage staff accounts, business & GST settings | ❌ | ✅ |

## 4. Invoice Lifecycle

States: `DRAFT → PENDING_APPROVAL → SENT → (OVERDUE) → PAID`

| From \ To | DRAFT | PENDING_APPROVAL | SENT | OVERDUE | PAID |
|-----------|-------|------------------|------|---------|------|
| DRAFT | — | ✅ submit (any role) | ✅ owner direct send | ❌ | ❌ |
| PENDING_APPROVAL | ✅ owner rejects (with note) | — | ✅ owner approve & send | ❌ | ❌ |
| SENT | ❌ | ❌ | — | ✅ auto (past due) | ✅ full payment |
| OVERDUE | ❌ | ❌ | ❌ | — | ✅ full payment |
| PAID | ❌ | ❌ | ❌ | ❌ | — |

All ❌ → HTTP 409 Problem Details naming the current status. Role violations
(staff attempting send) → 403 with `"Only the owner can send invoices."`

## 5. User-Facing Behaviour

### 5.1 Auth & team
Register = business name + owner email/password. Owner's Team page lists staff,
adds them (temp password shown once), deactivates them. First staff login forces
a password change before anything else. Copy: `"Set a new password to continue."`

### 5.2 Clients
A client holds: company name, contact person, email, phone, billing address,
**UEN**, and free-text **payment notes** (e.g. PayNow/bank details) — UEN and
payment notes print on the invoice document. Archive rule as before.

### 5.2a Business settings & GST
Business settings (owner): **default payment terms** (7/14/30 days — new
invoices default their due date to issue date + terms, editable per invoice),
plus GST-registered toggle + rate (default 9%).
Invoices of a registered business show `Subtotal / GST 9% / Total SGD`; the rate
is **snapshotted when the invoice is sent**, so later setting changes never
rewrite sent invoices. Unregistered businesses show no GST line.

### 5.3 Invoices
- **List**: columns number, status, client, total, balance due, due date
  (money mono right-aligned). Status filter tabs. Default view is
  **"needs attention first"**: OVERDUE, then PENDING_APPROVAL, then newest —
  the list is a to-do, not an archive.
- Builder (DRAFT): **side-by-side split** — form left, live paper-document
  preview right (stacks on mobile). Staff button:
  **Submit for approval**. Owner buttons: **Send** (direct) or via queue.
- PENDING_APPROVAL: read-only to staff; owner sees **Approve & send** /
  **Reject** (note required, invoice returns to DRAFT with the note visible).
- Editing a non-draft: `"Sent invoices can't be edited. Create a new invoice."`
- Numbering `INV-<year>-<seq>` per business, assigned at creation.

### 5.4 Payments (owner only)
Form captures amount, date, **method** (Bank transfer / PayNow / Cash /
Cheque), and a free-text note. Pre-fills remaining balance; overpay blocked:
`"Amount exceeds the remaining balance (S$1,047.80)."` Full payment → PAID,
toast `"Invoice INV-2026-0042 marked as paid."`

### 5.5 Validation layering
Every rule enforced twice: UI inline (convenience) and API (authoritative,
400/403/409 Problem Details).

## 6. Out of Scope

Multi-currency · real emails (send = status + document) · PDF export & public
invoice links (v2 — document layer is built to make this cheap) · credit notes ·
recurring invoices · multiple businesses per user · password reset flows beyond
forced first-login change.

## 7. Rollout Plan

Public GitHub repo to house standard. Phase 1 (API): ~7–8 weeks part-time while
learning Java/Spring. Phase 2 (UI): ~2.5 weeks. Deployed demo with a seeded
business (1 owner + 1 staff) so reviewers can try both roles.
