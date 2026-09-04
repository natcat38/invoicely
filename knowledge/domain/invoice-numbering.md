---
type: Domain Rule
title: Invoice Numbering
description: How invoice numbers are formed and allocated — per business, per year, assigned at creation and never reused.
resource: ../../docs/adr/0004-invoice-numbering.md
tags: [domain, invoice, invariants]
timestamp: 2026-09-05T00:00:00Z
---

# Schema

Format `INV-<year>-<seq>`, with the sequence zero-padded to four digits:
`INV-2026-0042`.

- **Per business, not global.** Two businesses may each hold an INV-2026-0001.
  The database enforces `UNIQUE (business_id, number)`.
- **Per year.** The sequence restarts at 0001 each January. The year comes from
  the invoice's *issue date*, so backdating files an invoice under that year.
- **Assigned at creation**, not at send, and never changed afterwards.
- **Never reused.** A deleted draft leaves its number unused; reusing it would
  give two documents the same identity.

Allocation takes the highest number already issued and adds one, serialised by a
pessimistic write lock on the business row — so there is no counter that can
drift out of step with the invoices it describes.

# Examples

A business issuing its third invoice of 2026 gets `INV-2026-0003`. If the second
was deleted while still a draft, the sequence still reads 0001, 0003 — gaps are
expected. Four digits caps a business at 9,999 invoices per year; past that,
allocation fails loudly rather than issuing a wider number that would sort
before every existing one.

# Citations

`docs/Invoice_Product_Scope.md` §5.3 · `docs/Invoice_Tech_Scope.md` §2 ·
`docs/adr/0004-invoice-numbering.md`. Numbers are assigned when the invoice is
created, before any of the transitions in
[Invoice lifecycle](/domain/invoice-lifecycle.md).
