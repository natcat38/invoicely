# ADR-0007 — Overdue is computed twice: a nightly bulk update, and a pure read-time check

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

`SENT → OVERDUE` is automatic (Product Scope §4): nobody clicks anything for
an invoice to start reading as overdue once its due date has passed. Something
has to decide, for every `SENT` invoice, whether "today" is now after its due
date, and that decision needs to be both **queryable** (the invoice list
filters on `status = OVERDUE`; the dashboard counts and sums overdue invoices,
Product Scope §2) and **immediately correct** for a single invoice a user is
looking at right now.

A single mechanism cannot satisfy both:

- **Compute on every read** (compare `dueDate` against today wherever status is
  shown) is always correct instantly, but a list or dashboard query can no
  longer filter or aggregate on `status` in SQL — it would have to load every
  `SENT` invoice and re-check the date in application code, which defeats the
  point of having a `status` column at all.
- **Persist via a scheduled job** lets every query stay a plain
  `WHERE status = 'OVERDUE'`, but the job runs once a day. Between midnight (or
  a due date passing) and the job's next run, a `SENT` invoice that is overdue
  in reality is still stored as `SENT` — visibly wrong if someone opens that
  exact invoice during the gap.

## Decision

**Run both, deliberately, and make sure they can never disagree about the
boundary.**

1. `OverdueInvoices.flipSentInvoicesPastDueToOverdue()` runs daily at 00:05
   Singapore time (`OVERDUE_CRON = "0 5 0 * * *"`, `OverdueInvoices.java:49`) —
   five minutes after midnight, so the date has definitely rolled over
   everywhere the job might run. It persists `SENT → OVERDUE` for every
   invoice whose due date is before today, across every business: this is a
   system job with no caller and no `CurrentRequest` to scope it to one
   business, and none is needed since the job's whole purpose is to catch all
   of them.
2. It is written as **one bulk JPQL `UPDATE`**, not a load-each-entity-and-call
   `setStatus` loop, so a business with thousands of overdue invoices costs one
   round trip instead of one per row. This is deliberately outside the normal
   `Invoice.setStatus`/`canTransitionTo` path used by request-driven
   transitions: a bulk update bypasses the persistence context, so it has no
   loaded `Invoice` entities to keep in step with what it just wrote. That is
   exactly why it is safe here — nothing else in the same transaction expects
   to see these invoices as `SENT` — and exactly why the same technique would
   not be safe for a request-driven transition like `send` or `reject`, which
   run inside a request that may already hold the invoice loaded.
3. `OverdueInvoices.asOf(Invoice, LocalDate)` is a second, pure method with no
   side effects: it reports what a single invoice's status should read as
   right now, checking the same `dueDate < today` condition, and is called
   wherever an individual invoice's status is shown or checked. It never writes
   to the invoice or the database, so calling it has no effect on when the
   job's own update happens — it only covers the display gap the job leaves
   open.
4. Both mechanisms use the **same comparison**, `dueDate < today` — strictly
   after, so an invoice due today is not overdue today, only from tomorrow —
   so they can never disagree with each other about the boundary. "Today" for
   both comes from `BusinessCalendar.today()` (ADR-0008), not the server clock
   directly.

## Consequences

- List and dashboard queries stay simple `status = OVERDUE` filters/aggregates,
  with no date arithmetic in the query layer, because the job keeps the
  persisted status caught up at least once a day.
- A single invoice viewed directly is never wrong for longer than the gap
  between its due date passing and the next 00:05 run — at most just under 24
  hours, and typically much less since `asOf` is checked on every read.
- The two mechanisms are two places that encode "past due" and must be kept
  in sync if that rule ever changes; both point at the same
  `dueDate < today` comparison and neither reads it from application config, so
  there is exactly one line to change in each, not a searchable string
  scattered across the codebase.
- The job flips status unconditionally for every business in one transaction.
  A single failing business or a huge overdue backlog delays the whole job's
  commit for everyone else, since there is no per-business batching. Acceptable
  at this application's scale (Product Scope's target is a handful of small
  businesses); would need chunking if that stopped being true.
- Because the bulk update bypasses the persistence context, nothing in the
  same request/transaction as the job can rely on Hibernate's first-level
  cache reflecting the just-flipped rows — not a live concern today since the
  job has no other work sharing its transaction, but worth remembering before
  adding any.
