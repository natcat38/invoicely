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
- [ ] Verification gate: full build + test run.

## OWNER-DECISION — not applied, needs Natalie's call
1. Rate limiting / lockout on `/auth/login` + `/auth/register` (new 429/lockout
   behaviour, thresholds to pick). security-review.md medium.
2. Invalidate JWTs issued before a password change (`iat` vs credentials-changed
   check — logs out all sessions on password change). security-review.md medium.
3. Deactivated accounts get 401 today; 403 is arguable. security-audit-raw.md minor.
4. Invoice "paper document" contract for Phase 2: client bill-to address in
   `InvoiceResponse`, business address/UEN (needs V2 migration), `amountPaid`
   visible to staff. architecture-review.md, OWNER-DECISION items.

## Deferred to Phase 2 start (technical, belongs in Phase 2 slice 1)
- CORS configuration (no frontend origin exists yet).
- `GET /auth/me` endpoint for session restore.
- Noted, not fixed (below-the-line per architecture review): `Invoice.setStatus`
  bypasses `canTransitionTo`; overdue predicate duplicated 6×; web-test fixture
  duplication.
