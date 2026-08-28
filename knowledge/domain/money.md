---
type: Domain Rule
title: Money
description: Representation and arithmetic rules for all monetary amounts — BigDecimal, single currency, derived totals.
tags: [domain, money, invariants]
timestamp: 2026-08-28T00:00:00Z
---

# Schema

- Storage: `NUMERIC(19,4)` in PostgreSQL; `BigDecimal` in Java; scale 2 at the
  API boundary; `RoundingMode.HALF_UP`. Never float/double.
- Single currency: SGD. No multi-currency, no tax computation (out of scope).

Derived values (never accepted as client input):

```
lineTotal    = quantity × unitPrice
subtotal     = Σ lineTotal
gst          = business GST-registered ? subtotal × gstRateSnapshot : 0
invoiceTotal = subtotal + gst
balance      = invoiceTotal − Σ payments.amount
```

GST is a business-level setting (registered + rate, default 9%). The rate is
**snapshotted onto the invoice at send time** and never rewritten — later
setting changes affect only future invoices.

# Examples

A payment may not exceed the remaining balance (400 on violation). A payment
that brings balance to exactly 0 triggers the PAID transition — see
[Invoice lifecycle](/domain/invoice-lifecycle.md).

# Citations

`docs/Invoice_Tech_Scope.md` §2 (worked scenarios table).
