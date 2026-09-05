# ADR-0011 — The invoice document renders from live data; only the GST rate is snapshotted

**Status:** accepted · **Date:** 2026-09-06 · **Decided by:** Natalie

## Context

The Design Direction makes the paper document the signature of the product: the
app chrome is deliberately quiet so that the invoice itself — serif number,
ruled ledger, totals block, status stamp — carries all the personality. Phase 2
renders that document live in the builder and on the detail page.

The Phase 1 audit found that no endpoint could actually supply it
(`reports/phase1-audit/architecture-review.md`, P2). A real invoice needs three
things `InvoiceResponse` did not have:

- a **bill-to block** — the client's address and contact, where only the name,
  UEN and payment notes were exposed;
- a **from block** — the business's own address and UEN, which did not exist in
  the database at all;
- **`amountPaid`**, which the Design Direction's totals block spells out as
  "subtotal → GST → total → payments → balance due". Only `balance` was
  exposed, so the "payments" row could not be drawn.

Adding the fields raised a second question the audit did not: an invoice is a
document that was sent on a date and should not silently change afterwards.
`gst_rate_snapshot` already works that way — frozen at send, never rewritten,
because the tax charged on a sent invoice is a fact about that invoice. Should
the letterhead and bill-to freeze too?

## Decision

Add the fields, and render them **live** — from the current `businesses` and
`clients` rows — with no new snapshot columns.

- `businesses` gains nullable `address` and `uen` (V2), editable on the owner's
  Settings page alongside the GST fields.
- `InvoiceResponse.ClientSummary` gains `address`, `contactPerson` and `email`.
- `InvoiceResponse` gains a `BusinessSummary` (name, address, UEN, whether GST
  registered) and a top-level `amountPaid`.
- `amountPaid` is visible to staff, like `balance` already is.

`gst_rate_snapshot` remains the one and only snapshotted field.

## Consequences

- **Correcting a typo in your own address fixes every invoice at once**, which
  is what a small business actually wants, and is the reason the client's
  address was already being read live for every other screen. The cost is the
  mirror case: reprinting an invoice from two years ago prints today's address,
  not the one the client was billed from.

- **That asymmetry is deliberate and worth stating plainly.** The GST rate is
  snapshotted because it determines *how much money is owed* — reprinting an
  old invoice at today's rate would restate a debt. An address determines
  nothing; it is presentation. Snapshotting it would mean six more columns, a
  copy step in the send transition, and a rule that the owner cannot fix a
  typo on a sent invoice — real complexity bought for a case (a legal dispute
  over which address appeared on a two-year-old PDF) that a portfolio
  invoicing app does not have.

- **This is the field to revisit first if the product ever exports PDFs and
  stores them.** At that point the stored PDF *is* the snapshot, and the
  question disappears rather than needing new columns.

- **The letterhead degrades rather than breaks.** Both business fields are
  nullable, so a business that has filled in nothing still renders a valid
  invoice — just a thinner one. The document must not print an empty line or
  the word "null" for a missing field; it omits the row. The same already
  applies to the GST line, which is omitted (not printed as 0.00) when the
  business is not registered.

- **Staff seeing `amountPaid` is a confirmed visibility rule, not an
  oversight.** Product Scope §3 restricts staff from *recording* payments and
  from the revenue dashboard; it does not hide the payment state of an invoice
  they drafted. Staff already saw `balance`, from which `amountPaid` was
  trivially derivable — exposing it directly removes an inconsistency rather
  than widening access.
