# ADR-0004 — Invoice numbers come from the highest number already issued, under a per-business lock

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

> The Tech Scope lists ADR-0004 under Task 5, but invoice numbers are assigned
> at creation (Product Scope §5.3), so the decision had to be made in Task 3
> when the create endpoint was built. Nothing else about Task 5 moved.

## Context

Invoice numbers are `INV-<year>-<seq>`, restarting each January and running
independently per business — two businesses may both hold an INV-2026-0001. The
database enforces this with `UNIQUE (business_id, number)`.

That constraint stops a duplicate being *stored*; it does not stop two
concurrent requests both deciding they should be number 0007, one of which then
fails. Something has to decide who gets which number.

The Tech Scope offered two shapes:

- **Compute, insert, retry on collision.** No extra state. But in PostgreSQL a
  failed insert poisons the whole transaction, so the retry has to start a new
  one — which means create becomes a loop around a transaction boundary rather
  than a method, and the interesting failure mode only appears under concurrency,
  where it is hardest to test.
- **A locked counter row.** Correct and retry-free, but it adds a table whose
  only job is to agree with the invoices table, and a counter that disagrees
  with reality is a genuinely unpleasant bug to diagnose.

## Decision

Take the highest number already issued and add one, with the allocation
serialised by a **pessimistic write lock on the business row**:

```java
Business business = businesses.findByIdForUpdate(businessId);   // SELECT ... FOR UPDATE
String number = numbering.next(businessId, issueDate);          // max + 1
```

- There is no counter. The invoices themselves are the record of what has been
  issued, so there is nothing that can drift out of step with them.
- The lock is taken on a row the create path has to load anyway, for the payment
  terms and the GST setting — so it costs no extra query.
- Sequences are zero-padded to four digits (`INV-2026-0042`, Product Scope
  §5.4), which makes the highest number also the last one alphabetically. Finding
  it is `max(number)` in SQL rather than a scan and a parse.
- The year comes from the invoice's **issue date**, not from today, so
  backdating an invoice into December files it under that year's sequence.
- The `UNIQUE (business_id, number)` constraint stays as the real guarantee. The
  lock decides who gets which number; the constraint makes it impossible to be
  wrong about it.

## Consequences

- Invoice creation is single-file **within one business**. Businesses never
  block each other. At the scale this application targets — a small business
  issuing a handful of invoices a day — the lock is uncontended in practice.
  If that ever stops being true, the fix is a counter row, not a retry loop.
- Four digits caps a business at 9,999 invoices in a year. Past that,
  `InvoiceNumbering` throws rather than issuing a five-digit number, because a
  wider number would sort *before* every four-digit one and quietly break the
  `max(number)` lookup this design rests on. Widening the format means
  backfilling the existing numbers too.
- **The design depends on READ COMMITTED**, PostgreSQL's default. Once the
  second transaction acquires the business lock, its next statement takes a
  fresh snapshot and sees the invoice the first one committed. Under REPEATABLE
  READ it would keep reading its original snapshot, miss that invoice, and
  allocate a number that already exists — caught by the unique constraint, but
  as an intermittent 500. Do not raise the isolation level without replacing
  this with a counter row.
- Numbers can have gaps. A create that fails after allocating still consumes
  nothing, but a deleted draft leaves its number unused, and the sequence does
  not reclaim it. That is normal for invoice numbering and is the safer
  behaviour — reusing a number would make two documents share an identity.
- Because numbers are assigned at creation rather than at send, a business that
  drafts more invoices than it sends will see gaps in what its clients receive.
  Product Scope §5.3 asks for assignment at creation, so this is intended.
