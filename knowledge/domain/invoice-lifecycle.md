---
type: Domain Process
title: Invoice Lifecycle
description: The invoice status machine — DRAFT, SENT, OVERDUE, PAID — and the rules governing each transition.
tags: [domain, invoice, state-machine]
timestamp: 2026-08-28T00:00:00Z
---

# Schema

States: `DRAFT`, `SENT`, `OVERDUE`, `PAID`.

| From \ To | DRAFT | SENT | OVERDUE | PAID |
|-----------|-------|------|---------|------|
| DRAFT | — | user sends | ✗ | ✗ |
| SENT | ✗ | — | auto: due date passed | full payment |
| OVERDUE | ✗ | ✗ | — | full payment |
| PAID | ✗ | ✗ | ✗ | — |

Illegal transitions are rejected in the service layer with HTTP 409.
Only DRAFT invoices have editable line items. SENT → OVERDUE is persisted by a
daily scheduled job **and** computed on read, so display is always correct.

# Examples

Sending: `POST /invoices/{id}/send` (DRAFT only). Payment that clears the
balance flips SENT/OVERDUE to PAID; see [Money](/domain/money.md).

# Citations

Source of truth: `docs/Invoice_Product_Scope.md` §5 decision matrix and
`docs/Invoice_Tech_Scope.md` §2 core logic.
