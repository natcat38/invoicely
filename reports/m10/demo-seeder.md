# M10 — Demo data seeder

## What was built

- `src/main/java/com/invoicely/DemoDataSeeder.java` — an `ApplicationRunner`
  (`@Component @Profile("demo")`) that seeds one GST-registered business
  ("Marina Bay Renovations Pte Ltd", 9% GST, address + UEN), an OWNER and a
  STAFF login (both with `mustChangePassword=false`, deliberately deviating
  from `TeamService`'s forced-change rule so a recruiter can sign in as
  either role immediately — commented at `seedStaff()`), 4 Singapore clients
  with address/UEN/PayNow-or-bank payment notes, and 7 invoices covering
  DRAFT, PENDING_APPROVAL (staff-authored, populating the owner's approval
  queue), two SENT, one OVERDUE (due date in the past via
  `BusinessCalendar.today()`), and two PAID (one settled on time, one same-day)
  with realistic contracting amounts. Credentials come from
  `invoicely.demo.owner-email` / `invoicely.demo.staff-email` (defaulted) and
  `invoicely.demo.password` (no default — a nested `@ConfigurationProperties`
  record's compact constructor throws `IllegalStateException` at startup if
  it's unset while the profile is active). Idempotency: `run()` looks up the
  owner's email first and returns early (logging at INFO either way) if the
  business already exists. GST snapshotting and status transitions are
  re-derived by hand (mirroring `InvoiceLifecycleService.send`) rather than
  calling the web-layer services, since those depend on `CurrentRequest`,
  which has no meaning at application startup.
- `src/test/java/com/invoicely/DemoDataSeederTest.java` — `@SpringBootTest`
  with `demo` profile + a test password, against Testcontainers Postgres.
  Verifies: both accounts exist, are active, and don't require a password
  change; calling `seeder.run(...)` a second time changes no row counts
  (businesses/users/invoices); every SENT/OVERDUE/PAID invoice has a non-null
  `gstRateSnapshot`; every PAID invoice's `InvoiceTotals` balance is exactly
  zero. All statuses (DRAFT, PENDING_APPROVAL, SENT, OVERDUE, PAID) are
  asserted present.

## Deviations (explained in code comments)

- Both demo accounts keep `mustChangePassword=false`, unlike `TeamService`'s
  normal staff-creation rule — required so both roles are usable on first
  login.

## Not done / out of scope

- Did not run `./mvnw` per the hard rule; the parent's verification gate
  should compile and run `DemoDataSeederTest`.
- No files outside the given list were touched.
