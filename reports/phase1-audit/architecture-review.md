# Invoicely — Phase 1 Architecture Review

**Scope:** the Spring Boot API at Tasks 1–6 (`main` @ `10b78a4`), read as preparation
for Phase 2 (the React UI, Tasks 7–9).
**Method:** read `docs/Invoice_Product_Scope.md`, `docs/Invoice_Tech_Scope.md`,
`docs/Invoice_Design_Direction.md`, all five ADRs, `knowledge/`, and every class in
`src/main/java/com/invoicely` and `src/test/java`. Cross-checked against the existing
`reports/phase1-audit/` reports (security, test gaps, ponytail) so nothing there is
repeated here. **No files were modified.**

Report format follows the brief (ranked Markdown at this path) rather than the
`improve-codebase-architecture` skill's default HTML-to-temp, since the deliverable
was specified.

---

## Verdict

**The architecture is sound and Phase 2 does not need a refactor to start.** Layering is
clean in the direction that matters — `domain` imports nothing from `web` or `security`
(verified by grep across all 17 domain files). The ownership boundary from ADR-0001 is
applied with genuine uniformity: every single-row lookup in all six services is
`findByIdAndBusinessId(id, currentRequest.businessId())`, with no bypass. Money lives in
one place (`InvoiceTotals`), the status table lives in one place (`InvoiceStatus`), and
the "effective status" subtlety — an invoice overdue in fact but still stored `SENT`
until the nightly job — is handled correctly and deliberately in *both* the read path and
the list query, which is the kind of thing most codebases get wrong.

What Phase 2 will actually hit is **not structure — it is contract**. The two things that
will stop the UI on day one are a missing CORS configuration and a set of fields the
paper-document layer needs that no endpoint returns. Both are additive; neither requires
touching the shape of the code.

The deeper items below (P4, P5, P6) are real but are *maintainability* wins, not Phase 2
blockers. I have marked them as such rather than inflating them.

**Ranked payoff:** P1 and P2 before writing any React. P3 shortly after. P4–P6 are
worth doing while the API is still fresh in mind, but Phase 2 can proceed without them.
P7–P9 are notes, not work.

---

## P1 — No CORS configuration exists. The UI cannot make a single request.

**Where:** `src/main/java/com/invoicely/security/SecurityConfig.java` (155 lines, no
`.cors(...)` call anywhere in the filter chain). Confirmed by grep: the strings `cors`,
`CrossOrigin` and `allowedOrigin` do not appear anywhere in `src/`, `pom.xml`, or
`compose.yaml`.

**Why it matters for Phase 2:** Task 7 is "Vite app, design tokens, auth". The Vite dev
server runs on its own origin (`http://localhost:5173`) and the API on `:8080`. Every
request the browser makes is cross-origin, and every one of them will fail the preflight
before it ever reaches a controller. The failure mode is a browser console CORS error
with no server-side log line at all, which is a genuinely confusing first hour for
someone learning Spring — it looks like the API is down when it is working perfectly.

This is not merely a dev-server concern. The deployed demo (Product Scope §7, "a seeded
business so reviewers can try both roles") will serve the static UI from wherever it is
hosted; unless that is the same origin as the API, the same wall applies in production.

**Recommendation:** add a `CorsConfigurationSource` bean to `SecurityConfig` and wire it
with `http.cors(withDefaults())`. Drive the allowed origins from a property on the
existing `SecurityProperties` record so local and deployed differ by configuration, not
by code — this matches how `invoicely.security.secret` is already handled. Allow the
`Authorization` request header, and **expose `Location`** in the response, since
`POST /invoices` and `POST /clients` both return it (`InvoiceController.java:50`) and a
browser cannot read an unexposed header.

Do not use `@CrossOrigin` annotations on controllers — with Spring Security in the chain
the config-level bean is the reliable placement, and one bean is easier to audit than
nine annotations.

> **OWNER-DECISION:** which origins to allow in the deployed demo. The safe default is an
> explicit allow-list from configuration; `allowedOriginPatterns("*")` combined with
> credentials is the one combination to avoid, though note that ADR-0002's bearer-header
> choice already makes this less dangerous here than it would be with cookies.

**Effort:** ~1 hour, plus a test asserting a preflight `OPTIONS` succeeds.

---

## P2 — The document layer's data contract is not met by any endpoint.

The Design Direction names the paper document as *"the signature"* of the product — the
one thing the app is built around, rendered live in the builder (Task 8) and reused
unchanged for the v2 PDF export. Three distinct pieces of what that document has to print
are not obtainable from the API today.

### (a) The "bill to" block has no address

**Where:** `src/main/java/com/invoicely/web/InvoiceResponse.java:70-77`. `ClientSummary`
carries `id, name, uen, paymentNotes` — and its Javadoc explains the trim as deliberate
("the full client record is a separate request"). But `ClientResponse`
(`ClientResponse.java`) proves the data exists: `contactPerson, email, phone, address`
are all stored and all returned by `/clients/{id}`.

Product Scope §5.2 lists billing address as a client field, and an invoice document
without the recipient's address is not a document anyone would send. So the builder's
live preview must either issue a second request per invoice (`GET /clients/{id}` on every
keystroke-triggered re-render, or a manual cache the UI now owns) or render an incomplete
document.

**Recommendation:** add `contactPerson`, `email` and `address` to `ClientSummary`. This is
purely additive, costs no extra query (the client is already loaded and
`default_batch_fetch_size=50` already covers the list case), and removes a whole class of
state-management work from Phase 2.

### (b) The invoice has no "from" block at all

**Where:** `src/main/resources/db/migration/V1__baseline.sql` — the `businesses` table is
`id, name, gst_registered, gst_rate, default_payment_terms_days, created_at`. There is no
address, no UEN, no contact detail. `SettingsResponse` returns
`id, name, gstRegistered, gstRate, defaultPaymentTermsDays` and nothing more.

So the document can print the issuing business's **name** and nothing else. The client's
UEN prints (Product Scope §5.2) but the issuer's does not — which is backwards for a
Singapore tax invoice, where the issuer's GST registration number is the number that
conventionally appears.

This is a genuine scope gap rather than an implementation miss: neither scope doc ever
lists a business address or UEN, so nothing was skipped. It only becomes visible now,
when someone has to actually draw the document.

> **OWNER-DECISION:** what the document's "from" block prints. Options, cheapest first:
> **(i)** business name only — accept that the demo document has a thin letterhead;
> **(ii)** add `address` + `uen` to `businesses` and the Settings page — one migration
> (`V2`), two DTO fields, two form fields, and the document gains a real letterhead;
> **(iii)** (ii) plus a conditional "GST Reg. No." line shown only when
> `gstRegistered` is true. Option (ii) is the one I would expect a reviewer of a
> portfolio invoicing app to look for, and it is small. But it adds product surface
> (a Settings form grows), so it is your call, not mine.

### (c) The totals block cannot be rendered by a staff member

**Where:** the Design Direction specifies the totals block as
*"subtotal → GST 9% → total SGD → payments → balance due"*. `InvoiceResponse`
(`InvoiceResponse.java:28-43`) returns `subtotal, gstRate, gst, total, balance` — there is
no `amountPaid`. The payments themselves come from
`GET /invoices/{invoiceId}/payments`, which is `@PreAuthorize("hasRole('OWNER')")`
(`PaymentController.java:46-47`).

A STAFF user opening an invoice detail page therefore gets four of the five lines and has
to leave a hole where "payments" goes. Note the restriction is already porous in the
direction that matters: staff *can* see `balance` and `total` on the same response, so the
paid amount is `total - balance` — one subtraction away. The owner-only gate on the
payments list is buying confidentiality of the *payment records* (method, date, note, who
recorded it), not of the amount.

**Recommendation:** add `amountPaid` to `InvoiceResponse`. `InvoiceTotals` already
computes the paid sum internally in order to derive `balance`
(`InvoiceTotals.java:40-54`), so this is exposing a value that exists, not adding a query.
The document then renders completely for both roles, and the payments *list* stays
owner-only, which is the line Product Scope §3 actually draws ("Dashboard / revenue
figures" is the owner-only row, not "the balance on an invoice you drafted").

> **OWNER-DECISION:** confirm that a staff member seeing "S$500.00 paid" on an invoice
> they drafted is intended. I read Product Scope §3 as yes — the owner-only row is about
> revenue reporting, and staff already see `balance` today — but it is a visibility rule,
> so it is yours to confirm.

**Effort for P2:** (a) and (c) together ~2 hours including test updates. (b) is a `V2`
migration plus Settings plumbing, ~half a day, and gated on the decision above.

---

## P3 — There is no way to re-identify the caller after a page reload.

**Where:** `AuthController.java` exposes exactly three endpoints — `/auth/register`,
`/auth/login`, `/auth/change-password`. There is no `GET /auth/me` (confirmed by grep).

**Why it matters for Phase 2:** `AuthResponse` (`AuthResponse.java:19-28`) is well designed
— it carries `userId, name, email, role, businessId, businessName, mustChangePassword`
precisely so the UI can render the sidebar and pick its routes without a second round
trip, and its Javadoc says so. The problem is that this body is returned **only at the
moment of login**. ADR-0002 then stores the token in `localStorage`, and ADR-0002 also
deliberately keeps display data *out* of the token ("they are display data; putting them
in a signed credential means they go stale").

So on a page refresh — the single most common thing a user does — the UI holds a valid
token and knows nothing else. Its only options are to persist the whole `AuthResponse`
into `localStorage` alongside the token and trust that copy for up to 12 hours, or to
force a re-login. The cached copy goes stale in three ways that all exist today: the
business is renamed via `PUT /settings`, the user's `mustChangePassword` is cleared in
another tab, or the account is deactivated (which `AccountStateFilter` will catch on the
next API call, but not before the shell has rendered a full app for a deactivated user).

**Recommendation:** add `GET /auth/me` returning the identity half of `AuthResponse`
(everything except `token` and `expiresAt`). It is ~30 lines: a controller method, a
record, and a `users.findByIdAndBusinessId(currentRequest.userId(), ...)` lookup that
`AuthService.changePassword` already performs. It costs one request at app boot, reuses
the `AccountStateFilter` that already runs per request, and lets Phase 2 treat the token
as the *only* thing it persists — which is a much simpler rule for the UI to hold.

This does not contradict ADR-0002; it is the natural complement to it. The ADR's argument
is that display data should not be *signed into a credential*, and an endpoint is exactly
the alternative that argument implies.

**Effort:** 1–2 hours including a test.

---

## P4 — The invoice's status invariant lives in its callers, not in the invoice.

**Where:**
- `Invoice.setStatus(InvoiceStatus)` — `src/main/java/com/invoicely/domain/Invoice.java:205-207`
  — is public and unguarded. It never consults `InvoiceStatus.canTransitionTo`.
- The legality check is `requireTransition` in
  `src/main/java/com/invoicely/web/InvoiceLifecycleService.java:103-109`, called
  immediately before `setStatus` at lines 54/58, 73/86, and 97/99.
- `src/main/java/com/invoicely/web/PaymentService.java:86` sets `PAID` **without** going
  through `requireTransition`. It is guarded instead by its own three checks
  (`hasBeenSent`, `== PAID`, `amount > balance`), which happen to be sufficient — `SENT →
  PAID` and `OVERDUE → PAID` are both legal — so this is not a live bug. It is the
  demonstration that the guard is forgettable.

There is a second, sharper version of the same shape in `send`
(`InvoiceLifecycleService.java:82-86`): becoming SENT means writing five fields —
`gstRateSnapshot`, `sentAt`, `sentBy`, `rejectionNote`, `status` — as five independent
statements. The invariant "a SENT invoice has a frozen GST rate and a recorded sender"
holds only because this one method writes all five in order. Nothing in `Invoice` enforces it,
and `InvoiceTotals.of` (`InvoiceTotals.java:40-54`) then *depends* on it, branching on
`hasBeenSent()` to decide whether to use the snapshot or the live business rate. A SENT
invoice with a null snapshot would silently make a GST-registered business's invoice
compute at the *live* rate forever — the exact bug the snapshot exists to prevent.

**Deletion test:** delete `requireTransition` and the ordering discipline in `send`, and
the complexity does not move somewhere else — it reappears as an obligation on every
future caller to remember two things. That is the signal for pulling it into the entity.

**Recommendation:** two small methods on `Invoice`, and make `setStatus` private:

- `transitionTo(InvoiceStatus target)` — consults `canTransitionTo`, throws a domain
  exception on refusal. `InvoiceLifecycleService` translates that to its 409; the message
  wording stays in the web layer where it belongs (the copy is user-facing, per
  `ApiException`'s own note that `detail` may be reworded).
- `markSent(BigDecimal gstRateSnapshot, User sentBy, Instant at)` — writes the five fields
  as one operation, so "SENT" and "has a snapshot" cannot come apart.

This is the change with the best ratio of clarity to size in the whole review, and it is
squarely aligned with the project's own goal of being readable by someone learning Java:
`invoice.transitionTo(SENT)` reads as the domain rule, where `requireTransition(invoice,
SENT); invoice.setStatus(SENT);` reads as two steps you must not separate.

**Not a Phase 2 blocker.** Do it because the API is fresh in mind, not because the UI needs it.

**Effort:** half a day including moving the relevant assertions in `InvoiceStatusTest` and
`InvoiceLifecycleApiTest`.

---

## P5 — "What makes an invoice overdue" is written out six times in two languages.

**Where** — the predicate `status = OVERDUE, or (status = SENT and dueDate < today)`
appears independently as:

1. `OverdueInvoices.asOf` — Java — `domain/OverdueInvoices.java:107`
2. the nightly bulk `UPDATE` — JPQL — `domain/OverdueInvoices.java:83-88`
3. `findForList`'s status filter — JPQL — `domain/InvoiceRepository.java:79-85`
4. `findForList`'s `ORDER BY` priority — JPQL — `domain/InvoiceRepository.java:90-96`
5. `sumOverdueBalance` — JPQL — `domain/InvoiceRepository.java:152-153`
6. `countOverdue` — JPQL — `domain/InvoiceRepository.java:165-166`

To be clear about what is *not* wrong here: all six agree, they are each individually
correct, and the reasoning behind the duplication is documented at length in
`InvoiceRepository`'s interface Javadoc and in `OverdueInvoices`. This codebase noticed
the problem. The `dueDate < today` boundary ("due today is not overdue today") is even
called out explicitly as needing to match across mechanisms.

The friction is **locality**: changing the rule — say, a three-day grace period before an
invoice is chased — is a six-site edit across two languages, where five of the sites are
inside string literals the compiler will not check, and where getting five of six right
produces a dashboard whose overdue count disagrees with its overdue amount.

**Recommendation:** the JPQL occurrences (2–6) can share one text. Java annotations accept
compile-time constant expressions, so a `String` constant on the repository concatenates
into every `@Query`:

```java
String IS_EFFECTIVELY_OVERDUE =
        "(i.status = com.invoicely.domain.InvoiceStatus.OVERDUE "
        + "or (i.status = com.invoicely.domain.InvoiceStatus.SENT and i.dueDate < :today))";
```

Five string literals collapse to one, and a Javadoc line on the constant ties it to
`OverdueInvoices.asOf` as the Java-side twin — leaving two statements of the rule, in the
two languages that genuinely need it, pointing at each other. That is about as good as
JPA allows without introducing a persisted derived column, which would be a worse trade
(a column that can silently disagree with the dates it is derived from).

Note the ordering `CASE` (site 4) also encodes a second rule — the "needs attention"
priority — which is fine to leave inline; only the overdue half is shared.

**Not a Phase 2 blocker.**

**Effort:** ~2 hours. The existing `OverdueInvoicesTest` and `DashboardApiTest` cover the
behaviour, so this is a safe mechanical change.

---

## P6 — The test pyramid is inverted, and every web test rebuilds the same fixture.

**Where:** of 13 test classes, **10 boot the full Spring context against a real Postgres
container** — the 8 classes in `src/test/java/com/invoicely/web/` plus `JourneyTest` and
`InvoicelyApplicationTests` (all `@SpringBootTest` + `@AutoConfigureMockMvc` +
Testcontainers), and 2 more are `@DataJpaTest` + Testcontainers (`DomainPersistenceTest`,
`OverdueInvoicesTest`). There are **no `@WebMvcTest` slice tests and no service-level unit
tests anywhere in the tree.** The genuinely fast tests are three:
`InvoiceNumberingTest`, `InvoiceStatusTest`, `InvoiceTotalsTest` (~330 lines total, no
context, no container).

Separately, each of the 8 web test classes independently `@Autowired`s the same four
repositories and redeclares a near-identical `@BeforeEach` that creates a business, an
owner, and a client. Only `TestTokens` and `TestcontainersConfiguration` are shared.

**Why this is worth a note now:** Phase 2 is where the API contract changes most — P2
alone adds fields to two response records, and every UI iteration that discovers a missing
field means another round. Each such round currently costs a full container boot to
verify. And the fixture duplication means a shape change to the seed data is an 8-file
edit.

**What I am explicitly *not* recommending:** do not replace the integration tests with
mocked unit tests. Tech Scope Task 6 asks for exactly this Testcontainers coverage, the
`JourneyTest` end-to-end run is the best single artifact in the repo for a reviewer to
read, and the cross-business isolation guarantees of ADR-0001 are only meaningfully
provable against a real database. The pyramid being top-heavy is a *consequence of the
right decision*, not a mistake.

**Recommendation:** the cheap, non-controversial half only — extract the shared
`@BeforeEach` into one fixture base class (or a `@TestConfiguration` seed component) that
the 8 web test classes extend. That concentrates the seed shape in one place and passes
the deletion test cleanly: delete it and the same 40 lines reappear eight times. Leave the
`@SpringBootTest` choice alone.

If suite wall-clock later becomes annoying, the lever to reach for first is ensuring all
10 classes share a single Spring context (identical annotations and property overrides, so
the context cache hits) — not converting them to mocks.

**Effort:** half a day for the fixture extraction.

---

## P7 — List endpoints return two different shapes. (Note, not work.)

`GET /clients` and `GET /invoices` return Spring Data `Page<T>` envelopes with pagination
metadata. `GET /team` (`TeamController.java:39`) and
`GET /invoices/{invoiceId}/payments` (`PaymentController.java:47`) return bare JSON
arrays. A TanStack Query layer written generically against "a list endpoint" will need to
special-case the latter two.

I would **leave this as it is**. Both bare-array endpoints return naturally bounded
collections — the staff of one small business, the payments against one invoice — and
paginating them would add ceremony for no benefit. The inconsistency is a five-minute
annoyance in Phase 2, and changing it is a breaking contract change for a cosmetic gain.

Two things worth doing instead, both trivial: note the distinction in the README's API
section so the Phase 2 author is not surprised, and **check the startup log for a Spring
Data warning about serializing `PageImpl` directly** — recent Spring Data versions warn
that the `Page` JSON shape is not a stable contract and steer callers toward an explicit
DTO. If that warning is present on Boot 4.1, it is better to learn it now than after the
UI has hard-coded field names off the envelope.

> **OWNER-DECISION** if you would rather have one uniform envelope everywhere. It is
> defensible for consistency's sake; I just do not think it pays for itself.

---

## P8 — Package structure will not strain in Phase 2. Do not repackage. (Note, not work.)

`com.invoicely.web` holds 37 classes: 6 application services, ~20 DTO records, 5 exception
types, the controllers, `CurrentRequest`, and `GlobalExceptionHandler`. Strictly, the
services are application services rather than web concerns, and the package name
undersells them.

I am not recommending a repackage, for a specific reason: **Phase 2 adds no Java to this
package.** Tasks 7–9 are a separate Vite/React application consuming the API over HTTP.
The pressure that would justify a `com.invoicely.invoice` / `com.invoicely.client` feature
split — more code arriving in this package — is not coming. Restructuring now would churn
every file's imports, invalidate the existing audit reports' line references, and buy
nothing Phase 2 can spend.

On AI/junior navigability, which the brief asks about specifically: this repo is already
unusually well set up for it. `FILE-MAP.md` gives an agent a directory index, `knowledge/`
gives it validated domain concepts, the ADRs record the non-obvious decisions, and — most
of all — nearly every class carries a Javadoc that argues for its own existence against
the simpler alternative. That last habit is doing more for navigability than any package
layout would.

The one cheap improvement, if you want it: `FILE-MAP.md` currently stops at top-level
directories, so `src` is a single row. Adding three rows for
`src/main/java/com/invoicely/{domain,web,security}` with a one-line purpose each would
give an agent the layer map without it having to infer one from 54 filenames.

---

## P9 — Housekeeping

- **There is no `ROADMAP.md`** in this repo, though the house standard (six stages:
  Define → Plan → Build → Verify → Review → Ship) expects one and the master template
  lives at `C:\Users\natal\Documents\Coding\REPO-ROADMAP.md`. With Phase 1 complete and
  Phase 2 about to start, this is a natural moment to add one — Phase 1 lands as a
  completed Build/Verify pass and Phase 2 gets a plan stage. Flagging only; I did not
  create it, per the read-only brief.
- The existing `reports/phase1-audit/` set (security review, test gaps, ponytail audit)
  is good and current. Nothing in this review contradicts any of them.

---

## Summary table

| # | Finding | Phase 2 blocker? | Effort | Owner decision? |
|---|---------|------------------|--------|-----------------|
| P1 | No CORS configuration | **Yes — day one** | ~1h | allowed origins |
| P2a | `ClientSummary` lacks address/contact/email | **Yes — document layer** | ~1h | no |
| P2b | `businesses` has no address/UEN for the "from" block | **Yes — document layer** | ~half day | **yes** |
| P2c | No `amountPaid`; payments list is owner-only | **Yes — document layer** | ~1h | **yes** |
| P3 | No `GET /auth/me` for re-identification after reload | Near-blocker | 1–2h | no |
| P4 | `setStatus` bypasses `canTransitionTo`; `send` writes 5 fields loose | No | ~half day | no |
| P5 | Overdue rule stated 6× in 2 languages | No | ~2h | no |
| P6 | No test slices; fixture duplicated across 8 classes | No | ~half day | no |
| P7 | `Page` envelope vs bare array | No — document it | ~15m | if changed |
| P8 | Services live in `web`; 37-class flat package | No — defer | ~15m (FILE-MAP) | no |
| P9 | No `ROADMAP.md` | No | ~30m | no |
