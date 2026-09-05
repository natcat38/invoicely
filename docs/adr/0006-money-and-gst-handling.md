# ADR-0006 — Money is `BigDecimal`, rounded once at the subtotal, and GST is snapshotted at send

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

An invoice's numbers have to survive two things a naive implementation gets
wrong: floating-point representation error, and a business's GST setting
changing after a document has already gone to a client.

Three questions had to be answered together, because they constrain each
other: what type holds an amount, when rounding happens, and which GST rate a
given invoice uses.

- **Representation.** `double`/`float` cannot represent most decimal fractions
  exactly (`0.1 + 0.2 != 0.3`), which is disqualifying for anything that has to
  reconcile against a bank statement to the cent. The choice is between
  `BigDecimal` and integer cents. Integer cents avoid rounding-mode questions
  entirely, but GST at 9% of a subtotal does not divide evenly into cents, so
  the rounding question comes back the moment tax is introduced — `BigDecimal`
  with an explicit rounding mode answers it once, in one place, instead of
  scattering integer-division rounding through every calculation site.
- **Where rounding happens.** Line totals could each be rounded to two decimal
  places before being summed, or summed unrounded and rounded once at the
  subtotal. Rounding per line is the more common instinct, but half-cents from
  several lines round independently and accumulate into a visible discrepancy
  against a total computed the other way (e.g. by the client, by hand).
- **Which GST rate applies.** A business's GST registration and rate are
  settings that can change over time (Settings page, Product Scope §3). An
  invoice references a business, so reading the rate live would mean a rate
  change today silently rewrites the tax on a document a client received
  months ago.

## Decision

**Amounts are `BigDecimal`, stored as `NUMERIC(19,4)`, rounded to money's two
decimal places with `HALF_UP` exactly once — at the subtotal, not per line.**
GST is snapshotted onto the invoice at send time and, once sent, is read only
from that snapshot, never recomputed from the business's current setting.

1. `LineItem.lineTotal()` stays unrounded (`NUMERIC(19,4)` gives headroom
   beyond the two decimal places money is displayed in). `InvoiceTotals.of()`
   sums the unrounded line totals and rounds *that* sum once
   (`InvoiceTotals.java:41-43`). GST is then computed off the rounded subtotal,
   so a line total is never independently rounded before it is added to
   anything else.
2. `roundMoney()` (`InvoiceTotals.java:83-85`) is the single rounding
   implementation every money figure this API reports goes through, so a line
   total and the subtotal it feeds can never round differently from each
   other.
3. Which rate applies is decided by whether the invoice **has been sent**, not
   by whether a snapshot happens to be present (`InvoiceTotals.applicableGstRate`,
   `InvoiceTotals.java:71-77`):
   - Not sent (a draft) — the business's *current* `gst_rate`/`gst_registered`
     setting, so editing a draft this morning reflects a rate change made this
     morning.
   - Sent — `gst_rate_snapshot`, copied onto the invoice at send time,
     **including when that snapshot is `null`** because the business was not
     GST-registered when it sent. A business that registers for GST afterwards
     must not grow a GST line on a document the client already holds.
4. `null` and `0.00` are kept distinct (`InvoiceTotals.hasGst()`): a business
   that is not GST-registered shows no GST line at all; a registered business
   on a 0% rate shows one reading `0.00`. Keeping the rate (not just the
   amount) is also what lets the document print a "GST 9%" label.

## Consequences

- Every money-bearing column is `NUMERIC(19,4)` in Postgres and `BigDecimal` in
  Java end to end — no `double`/`float` anywhere on the money path, and no
  silent narrowing at the JPA/JDBC boundary.
- Rounding lives in exactly one method (`InvoiceTotals.roundMoney`). A second
  rounding site anywhere else in the codebase would be a bug by construction,
  not just by convention.
- Because rounding happens once at the subtotal, a client re-deriving the total
  by summing line totals from the printed document and applying tax will match
  Invoicely's own number, even though Invoicely's internal line totals carry
  more precision than what is printed.
- The GST snapshot means an invoice's tax figures are permanently decided the
  moment it is sent. There is no path — by design — to retroactively change the
  GST on a sent invoice by changing the business's settings; a correction
  requires rejecting/re-sending or a credit note (out of scope for Phase 1).
- A draft's totals are not stable across edits if the business's GST setting
  changes while it sits in DRAFT or PENDING_APPROVAL — that is intended, since
  nothing has been shown to the client yet.
