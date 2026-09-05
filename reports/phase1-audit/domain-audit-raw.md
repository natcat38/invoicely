# Domain / Dashboard / Client / Scheduling audit — Phase 1

Scope: Business/BusinessCalendar/BusinessRepository, Client/ClientRepository,
OverdueInvoices, SchedulingConfig, ClientController/ClientService/ClientRequest/
ClientResponse, DashboardController/DashboardService/DashboardResponse,
StaffResponse/CreateStaffRequest/CreatedStaffResponse (+TeamService,
InvoiceRepository, InvoiceTotals, InvoiceSummaryResponse, CurrentRequest read
for context), PingController, OpenApiConfig, InvoicelyApplication. Tests
skimmed: ClientApiTest, DashboardApiTest, OverdueInvoicesTest,
DomainPersistenceTest.

## Findings

### [MAJOR] N+1 on every invoice-list read and the dashboard's awaiting-approval queue
- File: src/main/java/com/invoicely/web/InvoiceSummaryResponse.java:34-46, used from
  src/main/java/com/invoicely/web/DashboardService.java:58-62 and (by the same
  `InvoiceRepository.findForList`) the main invoice list endpoint
- Scenario: `InvoiceRepository.findForList` (InvoiceRepository.java:73-104) is a
  plain `select i from Invoice i ...` with no `join fetch` and no
  `@EntityGraph`. `InvoiceSummaryResponse.from` then calls
  `InvoiceTotals.of(invoice)`, which does
  `invoice.getLineItems().stream()` and `invoice.getPayments().stream()`
  (InvoiceTotals.java:41-51) — both are lazy `@OneToMany` collections
  (Invoice.java:106-112) — and also calls `invoice.getClient().getName()`
  (InvoiceSummaryResponse.java:41), where `client` is a lazy `@ManyToOne`
  (Invoice.java:57-59). None of these are fetched by the list query, so for a
  page of N invoices this costs 1 (list) + up to 3N queries (line items,
  payments, client) run one row at a time while the transaction is still open
  (`open-in-view` is off, but the mapping happens inside the
  `@Transactional(readOnly = true)` method, so it lazy-loads instead of
  throwing). This hits both `GET /dashboard`'s awaiting-approval queue (which
  is unpaged — `Pageable.unpaged()` at DashboardService.java:60 — so an
  unbounded queue makes this unbounded too) and the ordinary paged invoice
  list. DashboardApiTest and the invoice list tests assert correctness, not
  query count, so this is not caught by the existing suite.
- Fix: add a fetch-join or `@EntityGraph` on `findForList` covering
  `lineItems`, `payments`, and `client` (a single query with joins, de-duplicated
  via `Page`/`distinct` as needed), or add a dedicated projection query for the
  list view that computes totals in SQL instead of walking lazy collections in
  Java. At minimum, batch-fetch (`@BatchSize`/`hibernate.default_batch_fetch_size`)
  to turn N+1 into O(1) extra round trips if a full rewrite is out of scope for
  this task.

### [MINOR] Dashboard revenue/overdue figures have no test asserting query count / no N+1 guard
- File: src/test/java/com/invoicely/web/DashboardApiTest.java (whole file)
- Scenario: the test suite proves the four headline stats are numerically
  correct but never asserts how many SQL statements a `GET /dashboard` call
  issues, so the N+1 above (and any future regression reintroducing one) can
  land and stay green indefinitely.
- Fix: add a query-count assertion (e.g. Hibernate statistics or a
  `datasource-proxy`/`p6spy` counter) around the dashboard call with a handful
  of seeded invoices, asserting the count stays constant as the number of
  awaiting-approval invoices grows.

### [MINOR] Client hard-delete race: existsByClientId check-then-delete is not itself atomic against a concurrent invoice create
- File: src/main/java/com/invoicely/web/ClientService.java:87-94
- Scenario: `delete()` checks `invoices.existsByClientId(...)` then calls
  `clients.delete(client)` in the same `@Transactional` method. Under default
  READ_COMMITTED isolation, a concurrent transaction inserting the first
  invoice for this client between the check and the delete could commit after
  this transaction's delete commits, leaving an invoice pointing at a deleted
  client (or failing on the FK constraint, which is the likely actual
  outcome given `invoice_id`/`client_id` FKs). This is a narrow window and the
  DB's foreign key constraint is the real backstop, so it likely fails safe
  (delete rolls back or insert fails) rather than corrupting data — but the
  application-level guard is not actually race-proof by itself.
- Fix: rely on (and verify) the FK constraint as the true guarantee, or take a
  row lock on the client (`SELECT ... FOR UPDATE`, mirroring
  `BusinessRepository.findByIdForUpdate`'s pattern) before checking
  `existsByClientId`. Low priority given the FK constraint likely already
  prevents real corruption; worth a one-line ADR note if left as-is.

## Rejected / verified-correct

- Cross-business isolation: `ClientRepository.findByIdAndBusinessId` /
  `findForList` (both business-scoped), `InvoiceRepository` equivalents,
  `UserRepository.findByIdAndBusinessId`, and `CurrentRequest.businessId()`
  (reads only from the verified JWT claim, never from request body/params) —
  all correctly scoped; cross-business access returns 404 via
  `NotFoundException`, never 403. Confirmed by `ClientApiTest.crossBusinessAccessIsNotFound`
  and `DashboardApiTest.otherBusinessIsInvisible`.
- Client archive rule: `ClientService.delete()` correctly checks
  `invoices.existsByClientId(client.getId())` before allowing a hard delete and
  throws 409 `client-has-invoices` otherwise; confirmed by
  `ClientApiTest.deleteWithInvoicesConflicts` / `deleteWithoutInvoicesSucceeds`.
  `existsByClientId` itself isn't business-scoped, but that's safe: `client` was
  already loaded scoped to the caller's business, and an invoice can only
  reference a client in its own business.
- Dashboard "revenue this month": `sumPaymentsReceived` sums `Payment.amount`
  (not invoice totals) filtered by `Payment.paidAt` in `[firstOfThisMonth,
  firstOfThisMonth.plusMonths(1))` — correct half-open interval, no
  off-by-one, and `Payment.paidAt` is a plain `LocalDate` ("the day the money
  arrived ... not a clock reading"), so there is no Instant/LocalDate
  timezone-conversion bug in this comparison. `BusinessCalendar.today()` uses
  `Asia/Singapore` explicitly rather than server-local/UTC, avoiding the
  midnight-boundary bug the class's own Javadoc warns about. Confirmed by
  `DashboardApiTest.revenueCountsOnlyThisMonth`.
- Dashboard "overdue amount"/"overdue count": both use *effective* status
  (`status = OVERDUE or (status = SENT and dueDate < today)`), matching the
  invoice's displayed status via `OverdueInvoices.asOf`, so the two can never
  disagree with each other or with what the nightly job would eventually
  persist. "Past due" is strictly `dueDate < today` (due today ≠ overdue),
  consistently applied in `OverdueInvoices.flipSentInvoicesPastDueToOverdue`,
  `OverdueInvoices.asOf`, and both `InvoiceRepository` queries. Confirmed by
  `OverdueInvoicesTest.sentInvoiceDueTodayIsNotOverdue` and
  `DashboardApiTest.headlineStatsMatchTheWorkedExample`.
- Team per-staff aggregates: `TeamService.lastActive` correctly takes the max
  of `createdAt` (from `countAndLastCreatedByUser`) and `sentAt` (from
  `lastSentByUser`) per user id, both queries scoped to `businessId`, both
  using `group by` (not a global aggregate), and correctly returns null only
  when neither map has an entry for that user id (not swallowing a real zero).
  `invoicesCreated` defaults to 0, not null, for a staff member with no
  invoices. Two queries total regardless of team size — not N+1.
- Overdue scheduled job (`OverdueInvoices.flipSentInvoicesPastDueToOverdue`):
  runs `@Transactional`, is a single bulk JPQL `UPDATE` (no N+1, no loop),
  correctly constrained to `status = SENT and dueDate < :today` (does not pick
  up PENDING_APPROVAL/DRAFT/PAID, confirmed by
  `OverdueInvoicesTest.draftInvoicePastDueIsUntouched` /
  `pendingApprovalInvoicePastDueIsUntouched` / `paidInvoicePastDueIsUntouched`),
  correctly spans all businesses on purpose (no caller/business context to
  scope to — this is intentional and documented, confirmed by
  `OverdueInvoicesTest.jobSpansBusinesses`), and does not swallow any
  exception — no catch block in `OverdueInvoices` or `SchedulingConfig` at all,
  so a failure would propagate and fail the scheduled invocation loudly rather
  than silently skip a business.
- No silent-failure catch blocks found in any file in scope: `ClientService`,
  `DashboardService`, `TeamService`, `OverdueInvoices`, `SchedulingConfig`
  contain zero try/catch blocks.
- `BusinessCalendar`: single `ZoneId.of("Asia/Singapore")` constant used
  consistently everywhere "today" matters (`OverdueInvoices`,
  `DashboardService`, `InvoiceSummaryResponse`) — no server-local/UTC leakage
  found.
- Due-date default: `InvoiceService.java:80-81` —
  `issueDate.plusDays(business.getDefaultPaymentTermsDays())` — plain, correct,
  no off-by-one (not in the required file list, checked because Hunt Item 6
  named it explicitly).
- Transaction boundaries: `ClientService` and `TeamService` are both
  class-level `@Transactional`; `TeamService.create` (business lookup + user
  save) and `ClientService.create` (business lookup + client save) each run
  inside one transaction. `BusinessRepository.findByIdForUpdate` takes a
  pessimistic write lock specifically to serialize invoice-numbering
  concurrent creates (documented, ADR-0004) — correct pattern, not used by
  Client/Team creation because neither needs a race-free sequence number.
- `ClientRequest`/`ClientResponse`: field shapes match, `Boolean` (boxed)
  `archived` correctly defaults absent → false on both create and update, no
  business_id ever accepted from the request body.
- `PingController`, `OpenApiConfig`, `InvoicelyApplication`: no business logic,
  nothing ownership- or aggregate-related; no issues.
