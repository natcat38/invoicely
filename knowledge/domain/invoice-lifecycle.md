---
type: Domain Process
title: Invoice Lifecycle
description: The maker-checker invoice status machine — DRAFT, PENDING_APPROVAL, SENT, OVERDUE, PAID — and which role may drive each transition.
resource: ../../docs/Invoice_Tech_Scope.md
tags: [domain, invoice, state-machine, rbac]
timestamp: 2026-08-28T00:00:00Z
---

# Schema

States: `DRAFT`, `PENDING_APPROVAL`, `SENT`, `OVERDUE`, `PAID`.

| From \ To | DRAFT | PENDING_APPROVAL | SENT | OVERDUE | PAID |
|-----------|-------|------------------|------|---------|------|
| DRAFT | — | any role submits | owner direct send | ✗ | ✗ |
| PENDING_APPROVAL | owner rejects (note required) | — | owner approve & send | ✗ | ✗ |
| SENT | ✗ | ✗ | — | auto: past due | full payment |
| OVERDUE | ✗ | ✗ | ✗ | — | full payment |
| PAID | ✗ | ✗ | ✗ | ✗ | — |

Two failure axes, tested separately: illegal state transition → 409; legal
transition by the wrong role → 403 (see [Business & roles](/domain/business-and-roles.md)).
Only DRAFT invoices have editable line items. Sending snapshots the GST rate
(see [Money](/domain/money.md)). SENT → OVERDUE is persisted by a daily job and
also computed on read.

# Examples

Staff flow: `POST /invoices/{id}/submit` → owner sees it in the approval queue →
`POST /invoices/{id}/send` or `POST /invoices/{id}/reject` (note returns it to
DRAFT). Owner may also send their own DRAFT directly.

# Citations

`docs/Invoice_Product_Scope.md` §4 · `docs/Invoice_Tech_Scope.md` §2.
