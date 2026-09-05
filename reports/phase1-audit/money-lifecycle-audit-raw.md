# Money/Lifecycle Correctness Audit — raw findings

Scope: Invoice/LineItem/Payment domain + lifecycle/payment services + controllers +
DTOs + V1 migration, cross-checked against Invoice_Tech_Scope.md and
Invoice_Product_Scope.md.

## Findings

### [MAJOR] No row lock on the invoice during payment recording — concurrent payments can overpay
- File: src/main/java/com/invoicely/web/PaymentService.java:59-100
- Scenario: Invoice INV-2026-0042 has balance S$1,000.00. Owner double-clicks
  "Record payment" (or two staff-owned devices/tabs race), firing two
  `POST /invoices/42/payments` requests for S$900.00 each, arriving in two
  separate transactions at roughly the same time. `load()` (line 118-121) does
  a plain `findByIdAndBusinessId` with no `@Lock`. Both transactions read the
  invoice (and its lazily-loaded `payments` collection) before either commits,
  so both compute `balance = 1000.00` via `InvoiceTotals.of(invoice)` (line 70),
  both pass `request.amount().compareTo(balance) > 0` (900 ≤ 1000), and both
  append a payment and commit. The invoice ends up with two payments totalling
  S$1,800.00 against a S$1,000.00 total — an 800.00 overpay that the "amount
  cannot exceed remaining balance" rule (Product Scope §5.4) was supposed to
  prevent, and neither payment's `balanceAfter` check (line 83-87) will ever
  see the value produced by the other transaction's write, so the flip-to-PAID
  logic can also disagree with the true balance afterward (a negative true
  balance while status stays SENT, or a false PAID if both writes race to
  exactly zero on paper but are actually one payment short).
- Compare: `InvoiceService.create` takes exactly this kind of lock on
  `Business` via `BusinessRepository.findByIdForUpdate` (PESSIMISTIC_WRITE) to
  serialise invoice-number allocation (see InvoiceNumbering.java javadoc) —
  the same pattern is absent here even though money is at stake, not just a
  formatted string.
- Fix: acquire a pessimistic write lock on the `Invoice` row in
  `PaymentService.record` before computing the balance (e.g. an
  `@Lock(PESSIMISTIC_WRITE)` finder analogous to
  `BusinessRepository.findByIdForUpdate`, scoped by `id` + `business_id`), or
  add a version column (`@Version`) to `Invoice` and let optimistic locking
  turn the second commit into a retryable conflict instead of a silent
  overpay. This is untested today — grepped the test suite for
  concurrency/race coverage (`Thread`, `ExecutorService`, etc.) across
  `src/test` and found none; `PaymentApiTest` only exercises the single-request
  overpay-by-one-cent case (line ~148-159), not concurrent requests.

### [MINOR] `InvoiceNumbering.next` reads `highestNumber` via the injected `InvoiceRepository`, not through the same locked `Business` handle callers already hold
- File: src/main/java/com/invoicely/domain/InvoiceNumbering.java:45-60, src/main/java/com/invoicely/web/InvoiceService.java:73-86
- Scenario: this is not currently exploitable — `InvoiceService.create` takes
  `findByIdForUpdate` on `Business` *before* calling `numbering.next(...)`, and
  Postgres READ COMMITTED means the second creator blocks on that same lock
  and, once granted, takes a fresh snapshot that sees the first invoice's
  commit (exactly as the class javadoc at lines 18-24 explains). I checked
  this carefully: it is correct as designed, and explicitly ADR'd
  (docs/adr/0004). Listing it only because `InvoiceNumbering` itself has no
  compile-time guarantee it is always called from inside such a lock — a
  future caller (e.g. a bulk-import feature) that calls `numbering.next`
  without first taking `findByIdForUpdate` would silently reintroduce the
  race the comment warns about. Consider a runtime assertion or, at minimum,
  a comment at the call site in `InvoiceService` cross-referencing this
  requirement (partially already present). No action needed today; flagging
  only as a maintainability trap, not a live bug.

### [MINOR] Dashboard aggregate queries recompute GST/line-item sums as unrounded BigDecimal, can drift from the sum of per-invoice rounded balances
- File: src/main/java/com/invoicely/domain/InvoiceRepository.java:111-156
- Scenario: `sumOutstandingBalance`/`sumOverdueBalance` multiply raw
  `line_items.quantity * unit_price` sums by `(1 + gstRateSnapshot)` in SQL
  without ever rounding to 2dp, then subtract raw payment sums. For a
  dashboard with many invoices, this total can differ by a few cents from
  summing each invoice's own `InvoiceTotals.balance()` (which rounds the
  subtotal before applying GST — see InvoiceTotals.java:41-53). E.g. an
  invoice with subtotal-before-rounding 100.005 rounds to 100.01 per-invoice
  (HALF_UP) but contributes 100.005 unrounded to the dashboard sum. The
  repository's own Javadoc (lines 124-128) already discloses and accepts this
  trade-off for a "headline figure," so I am not raising it as a bug, but
  flagging it because the audit brief asked to hunt for exactly this
  divergence pattern. No fix needed unless the dashboard is later expected to
  reconcile penny-for-penny with the invoice list.

### [MINOR] `sumOutstandingBalance`/`sumOverdueBalance` use correlated subqueries per invoice (N+1-shaped SQL, not app-level N+1)
- File: src/main/java/com/invoicely/domain/InvoiceRepository.java:130-156
- Scenario: each query embeds two correlated subqueries (line items sum,
  payments sum) evaluated per outer row. This is a single round trip from the
  application's perspective (no Java-level loop), so it is not the classic
  N+1 the audit brief was watching for, but for a business with thousands of
  SENT/OVERDUE invoices this SQL shape can be materially slower than a joined
  aggregate. Purely a performance note, not a correctness one — no fix
  required for Phase 1 scale (Product Scope: "a small business... not a
  second").

## Rejected / verified-correct

- GST snapshot lifecycle is correct end to end: `InvoiceTotals.applicableGstRate` (InvoiceTotals.java:71-77) uses the live `Business` rate pre-send and the frozen `gstRateSnapshot` post-send, keyed off `Invoice.hasBeenSent()` (`sentAt != null`) rather than off whether the snapshot happens to be non-null — this correctly handles an unregistered business that registers after sending (verified against `InvoiceTotalsTest.theSnapshotWinsOverTheLiveSetting`).
- The snapshot can never be overwritten by a second send: `InvoiceStatus.ALLOWED` has no self-transition and no path back into SENT from SENT/OVERDUE/PAID, so `InvoiceLifecycleService.send` can only run once per invoice (verified in InvoiceStatus.java:52-57 and InvoiceStatusTest's `nothingTransitionsToItself`/`nothingReturnsToAnUnsentState`).
- Full transition matrix (InvoiceStatus.java `ALLOWED` map) matches Product Scope §4 exactly, cell by cell — no missing legal transition, no wrongly-allowed one. Cross-checked every row against the product doc table.
- Role (403) vs state (409) precedence is correct: `@PreAuthorize` is evaluated by the Spring Security method interceptor before the target method body runs, so a staff member hitting `/send` or `/reject` on any invoice always gets 403 before the state check in `InvoiceLifecycleService` ever runs (InvoiceController.java:100-111). Documented explicitly in InvoiceStatus.java's javadoc and consistent with the actual annotation placement.
- `PAID` status is set via `compareTo(BigDecimal.ZERO) == 0` (PaymentService.java:84), not `.equals()`, so a stored value of scale 4 (e.g. `0.0000`) correctly compares equal to `BigDecimal.ZERO` (scale 0) — avoids the classic `equals()` scale-sensitivity bug.
- Balance-after-payment is recomputed from the invoice's live `payments` collection after the new payment is attached (PaymentService.java:83), not derived by subtracting from the pre-payment balance — correct per the code's own comment, avoids compounding a stale figure.
- Overpay check uses `compareTo`, not `==`/`.equals()`, and compares against the currently-loaded balance, not a stale total (PaymentService.java:71).
- Rounding: `InvoiceTotals` uses `BigDecimal` throughout, `HALF_UP`, rounds once at the subtotal boundary rather than per line (matches Tech Scope §2 and the class's own javadoc); verified against `InvoiceTotalsTest.roundingIsAppliedOnceToTheSubtotal` (3 × 0.005 → 0.02, not 0.03).
- No `double`/`float` anywhere in the money path (Invoice, LineItem, Payment, InvoiceTotals, InvoiceRequest, RecordPaymentRequest all use `BigDecimal`).
- Column precision/scale consistency: `line_items.quantity`/`unit_price` and `payments.amount` are `NUMERIC(19,4)` in V1__baseline.sql and `@Column(precision = 19, scale = 4)` in the entities; `businesses.gst_rate` and `invoices.gst_rate_snapshot` are `NUMERIC(5,4)` and `precision = 5, scale = 4` respectively — matches Tech Scope §3 exactly.
- Zero/negative line item quantity and price: DB `check (quantity > 0)` and `check (unit_price >= 0)` in V1__baseline.sql are mirrored by Bean Validation `@DecimalMin("0.0001")` on quantity and `@DecimalMin("0.00")` on unitPrice in InvoiceRequest.LineItemRequest — client gets a 400 before ever reaching the DB constraint.
- Empty line items on send: `InvoiceLifecycleService.send` explicitly rejects an empty `lineItems` list (409 `invoice-has-no-lines`); in practice this is unreachable via the API today since invoice create/update both require `@NotEmpty` line items and only DRAFT is editable, but the defensive check is harmless and correct.
- Invoice numbering race (the two-concurrent-creates case) is correctly handled: `InvoiceService.create` takes a `PESSIMISTIC_WRITE` lock on the `Business` row (`BusinessRepository.findByIdForUpdate`) before calling `InvoiceNumbering.next`, serialising number allocation per business under Postgres READ COMMITTED, as documented in InvoiceNumbering.java's javadoc and ADR-0004. The unique constraint `(business_id, number)` remains as a backstop.
- Overdue transition: the daily `@Scheduled` bulk `UPDATE` (`OverdueInvoices.flipSentInvoicesPastDueToOverdue`) and the computed-on-read `OverdueInvoices.asOf` use the identical `dueDate < today` comparison and the same `BusinessCalendar.today()` (Asia/Singapore) source, so they can never disagree about the boundary date. The bulk update correctly bypasses the persistence context (justified in its own javadoc) and is not used inside any request that holds a loaded `Invoice`, so there's no stale-entity-overwrite risk. `PaymentService.record` and `InvoiceLifecycleService` read `invoice.getStatus()` (the stored column) directly rather than the effective status when deciding legality — this is fine because both SENT and OVERDUE are legal predecessors of PAID in the transition matrix, so a payment on an invoice that's overdue-in-fact-but-still-SENT-in-storage is not mishandled.
- `InvoiceRepository.findForList` and the dashboard aggregate queries consistently use the effective status (`OVERDUE`, or `SENT and dueDate < today`) for both filtering and sorting, so the list and an individual invoice's displayed status can never disagree.
- Invoice list N+1: `InvoiceService.list` relies on `hibernate.default_batch_fetch_size` (per its own comment) to batch-fetch line items/payments across a page rather than one query per invoice; did not independently verify the application.properties setting but the comment states the intent and mechanism correctly and this is the standard Hibernate fix for this shape of N+1.
- BusinessId ownership scoping: every invoice/payment query I read goes through `findByIdAndBusinessId` (InvoiceService, InvoiceLifecycleService, PaymentService) or filters `where i.business.id = :businessId` (InvoiceRepository custom queries) — no missing business_id filter found in the files reviewed. `created_by`/`sent_by`/`recorded_by` are used only for display/audit, never in a `WHERE` clause that gates access.
- Payment amount validation (`@Digits(integer = 17, fraction = 2)`) matches the 2dp API boundary; `PaymentResponse.from` also explicitly re-rounds via `InvoiceTotals.roundMoney` even though the stored value should already be 2dp-equivalent, which is a harmless belt-and-suspenders move, not a bug.
