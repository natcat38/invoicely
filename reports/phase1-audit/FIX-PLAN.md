# Phase 1 hardening — consolidated fix plan

Source reports (this directory): security-review.md, security-audit-raw.md,
money-lifecycle-audit-raw.md, domain-audit-raw.md, test-gaps-raw.md,
ponytail-audit.md, architecture-review.md, docs-audit.md.

Branch: `phase1-hardening`. One verification gate (full test run) before commit.

## Wave 3 — apply now (no owner decision needed)

### Agent A — code fixes (main sources)
- [x] Payment race: lock the invoice row in `PaymentService.record` (reuse the
      `findByIdForUpdate` pattern used for numbering). Add a concurrency test
      proving aggregate overpay is now blocked. (money-lifecycle-audit-raw.md, major)
      — added `InvoiceRepository.findByIdAndBusinessIdForUpdate`
      (`@Lock(PESSIMISTIC_WRITE)`), used it in `PaymentService.record`; new
      `PaymentConcurrencyTest`.
- [x] N+1: `InvoiceRepository.findForList` has no fetch join/entity graph;
      `InvoiceSummaryResponse.from` lazy-loads line items/payments/client per row.
      Fix for both the paged list and the unpaged awaiting-approval queue.
      (domain-audit-raw.md, major)
      — `findForList` now `left join fetch`es `client` (to-one, safe with
      pagination); `lineItems`/`payments` stay lazy and batch-load via the
      existing `hibernate.default_batch_fetch_size=50`. (A true fetch-join on
      the to-many collections hit Postgres's "SELECT DISTINCT / ORDER BY"
      restriction and Hibernate 7 dropped the `passDistinctThrough` hint that
      used to work around it — this hybrid gets the same O(1)-per-page result
      without that fragility.) Covers both call sites since
      `DashboardService.get`'s awaiting-approval queue calls the same method.
- [x] Login timing side-channel: run BCrypt against a dummy hash when the email
      is unknown. (security-review.md, low — invisible to users, pure hardening)
- [x] Dead code deletions per ponytail-audit.md: `InvoiceStatus.isIssued()` (+ its
      test), `CurrentRequest.role()`, `LineItem` setters, plus the 3 LOW items.
      — Note: `LineItem.getPosition()` was NOT dead (used via method reference
      `LineItem::getPosition` in `DomainPersistenceTest`, missed by the audit's
      grep since it has no call-site parens) — kept it, deleted the rest
      (`getInvoice()`, `Invoice.getSentBy()`, folded `SchedulingConfig` into
      `InvoicelyApplication`).
- [x] Cheap non-behavioral minors from money/domain/security raw reports.
      — None applied beyond the above: every other MINOR/LOW item in the three
      raw reports is explicitly "no fix needed" / informational in the report
      itself, or is an owner/product-level call (email enumeration, client
      hard-delete lock) out of a "cheap non-behavioral" fix's scope.

### Agent B — test gaps (test tree only)
From test-gaps-raw.md: all 3 critical (concurrent invoice-numbering lock test;
pay-an-already-PAID-invoice 409 test; deactivated-user replays already-issued
JWT test) + the major gaps. Skip the payment-race concurrency test (Agent A owns it).

- [x] Done. Test tree only, no main sources touched. All 3 criticals +
      5/5 majors in test-gaps-raw.md added (the "6th major" is the
      payment-race test, owned by Agent A and explicitly skipped here):
      `InvoiceNumberingConcurrencyTest` (new, real ExecutorService + MockMvc
      concurrency), `PaymentApiTest` (+2: already-PAID 409, staff-vs-draft
      role-before-state), `AuthApiTest` (+2: token-before-deactivation replay,
      must-change-password across a spread of endpoints), `SettingsApiTest`
      (+1: cross-business isolation), `InvoiceLifecycleApiTest` (+1: GST
      re-registration on a fresh invoice), `InvoiceTotalsTest` (+2: HALF_UP
      exact-boundary, fractional-quantity rounding). All 6 classes run green;
      no disabled tests — every gap was coverable against the existing
      implementation with no main-source change needed.

### Agent C — docs — DONE
From docs-audit.md: fixed 4 README issues (CI badge, `/clients` and
`/invoices` query params, `reports/` added to FILE-MAP.md); wrote the 4
missing ADRs (`docs/adr/0006-money-and-gst-handling.md`,
`0007-overdue-status-dual-mechanism.md`, `0008-timezone-hardcoded-singapore.md`,
`0009-owner-staff-role-model.md`) and linked them from README's "Design
decisions" section.

## Wave 4 — after wave 3 merges cleanly
- [x] OpenAPI per-endpoint annotations on all controllers (docs-audit.md).
- [x] Verification gate: full build + test run. Green; merged as PR #9.

## OWNER-DECISION — answered 2026-09-06, all four built in Task 7a
1. [x] Rate limiting / lockout on `/auth/login` + `/auth/register`.
   → **Simple in-memory per-IP throttle**: 10 failures per IP per 15 minutes,
   then 429 with `Retry-After`. Per instance, lost on restart — stated as a
   known limit, not hidden. ADR-0010.
2. [x] Invalidate JWTs issued before a password change.
   → **Yes**: `users.password_changed_at` (V2) vs the token's `iat`, checked in
   `AccountStateFilter` → 401 `token-superseded`. Note it does *not* log the
   caller out of the session they are in — `change-password` already returns a
   fresh token stamped after the change, so Phase 2's forced-change
   interstitial needs no re-login screen. ADR-0010.
3. [x] Deactivated accounts get 401 today; 403 is arguable.
   → **403** `account-deactivated`. The UI keys on the problem `type` to know
   this particular 403 must clear the token. ADR-0010.
4. [x] Invoice "paper document" contract for Phase 2.
   → **Full document**: `businesses.address` + `uen` (V2) editable in Settings,
   client bill-to fields and a business letterhead block on `InvoiceResponse`,
   `amountPaid` exposed and visible to staff. Rendered **live**, not
   snapshotted — `gst_rate_snapshot` stays the only frozen field, and ADR-0011
   records why that asymmetry is deliberate.

## Deferred to Phase 2 start (technical, belongs in Phase 2 slice 1)
- [x] CORS configuration — explicit allow-list from
  `invoicely.security.allowed-origins`, defaulting to Vite's
  `http://localhost:5173`; no `allowCredentials`, since ADR-0002 puts the token
  in a header rather than a cookie. Built in Task 7a.
- [x] `GET /auth/me` endpoint for session restore. Built in Task 7a.
- Noted, not fixed (below-the-line per architecture review): `Invoice.setStatus`
  bypasses `canTransitionTo`; overdue predicate duplicated 6×; web-test fixture
  duplication.
