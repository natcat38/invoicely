# Documentation Audit — Phase 1 (Tasks 1–6)

Read-only audit. No source files were modified to produce this report.

**Summary counts:** 4 README/top-level-doc issues, 4 missing ADRs, 0 knowledge/
drift instances, 1 OpenAPI documentation gap, 0 Javadoc contradictions found
in the sampled files.

Overall picture: the Java source is unusually well commented and the four
existing `knowledge/` concept files match current code closely — no
contradictions were found there. The gaps are almost all *absence* rather
than *rot*: decisions that should have an ADR under the project's own rule
don't have one yet, and the OpenAPI layer has no per-endpoint annotations at
all (only a global `Info`/security-scheme bean).

---

## 1. README.md accuracy vs actual code

Verified against `pom.xml`, `application.properties`, `compose.yaml`,
`.github/workflows/ci.yml`, and every `@RequestMapping`/`@GetMapping`/etc. in
`src/main/java/com/invoicely/web/*Controller.java`.

**Matches confirmed (no issue):**
- Stack line ("Java 25 · Spring Boot 4.1 · … PostgreSQL 16 · … springdoc-openapi
  · Docker Compose · GitHub Actions") matches `pom.xml` (`java.version=25`,
  parent `4.1.1`, springdoc `3.1.0`) and `compose.yaml` (`postgres:16`).
- `docker compose up -d` / `.\mvnw spring-boot:run` / `.\mvnw verify` all
  correspond to real, working commands for this project (Testcontainers-based
  `verify`, compose file has no other services).
- Swagger UI path `http://localhost:8080/swagger-ui.html` matches
  `application.properties:30` (`springdoc.swagger-ui.path=/swagger-ui.html`).
- Every endpoint in the "API tour" table matches an actual
  `@RequestMapping`/method mapping, and every role annotation ("owner only" vs
  "any role") matches the corresponding `@PreAuthorize("hasRole('OWNER')")`
  (or its absence) in `ClientController`, `InvoiceController`,
  `PaymentController`, `TeamController`, `SettingsController`,
  `DashboardController`.
- The five ADR links in "Design decisions" correspond 1:1 to the five files
  actually in `docs/adr/` — no stale or missing link either way.
- CI reference ("CI (`.github/workflows/ci.yml`) runs the same command on
  every push and pull request") matches the actual workflow (`ci.yml:1-13`,
  triggers on `push`/`pull_request`, runs `./mvnw --batch-mode verify`).

**Issues found:**

1. **No CI badge at all.** `README.md` (top of file, no badge markdown present)
   — a public-facing repo with a working `ci.yml` and no status badge is a
   missed, low-effort signal for recruiters skimming the repo. Not "stale,"
   just absent.

2. **`GET /clients` row omits its query parameters.** `README.md:62` lists only
   `GET /clients` with no mention of `archived`, `q`, `page`, `size` — all four
   are real, documented-in-code parameters
   (`src/main/java/com/invoicely/web/ClientController.java:52-61`). A reader
   skimming the table would not know client search/pagination/archive-filtering
   exists at all.

3. **`GET /invoices` row omits its query parameters.** `README.md:63` — same
   gap: `status`, `clientId`, `page`, `size`
   (`src/main/java/com/invoicely/web/InvoiceController.java:60-71`) are not
   mentioned, including the "needs attention" default sort order that's
   actually documented in the controller's own Javadoc.

4. **`FILE-MAP.md` is stale against its own maintenance rule.** `FILE-MAP.md:1-4`
   says "update it in the same commit that adds or moves a top-level
   directory," but the `reports/` directory (containing prior audit outputs)
   is a top-level directory not listed in the table (`FILE-MAP.md:7-13`). Minor,
   but it's the one doc in the repo that explicitly promises to stay current.

---

## 2. docs/adr/ — missing ADRs

Existing ADRs (5): `0001-business-as-ownership-boundary.md`,
`0002-jwt-shape-and-storage.md`, `0003-temporary-password-flow.md`,
`0004-invoice-numbering.md`, `0005-java-25-spring-boot-4.md`.

The project's own working agreement (`CLAUDE.md`: "One ADR in `docs/adr/` per
non-obvious decision, written when decided") is not fully honored — several
non-obvious decisions, visible both in code and called out in
`README.md`'s "A few things worth pointing out" section, have no ADR:

1. **Overdue-status computation (dual mechanism)** —
   `src/main/java/com/invoicely/domain/OverdueInvoices.java:11-33` implements
   a genuinely non-obvious design: a nightly `@Scheduled` bulk `UPDATE` that
   *persists* `SENT → OVERDUE`, plus a separate pure `asOf()` method that
   *computes* the same answer on read to cover the gap before the job runs.
   The Javadoc explains the reasoning well, but the decision itself (why two
   mechanisms instead of one, why the job uses a bulk JPQL update instead of
   loading entities) is exactly ADR material and has none.

2. **Money handling / GST-snapshot rule** — `BigDecimal`, `NUMERIC(19,4)`
   storage, `HALF_UP` rounding applied once at the subtotal (not per line),
   and the "snapshot GST at send, including when it's null" rule in
   `src/main/java/com/invoicely/domain/InvoiceTotals.java:15-25,71-77` are all
   foundational, hard-to-reverse decisions with real trade-offs (documented
   well in Javadoc and in `knowledge/domain/money.md`, but never in an ADR).

3. **The two-role (OWNER/STAFF) maker-checker authorization model itself** —
   ADR-0001 covers *cross-tenant* isolation (business boundary → 404), but the
   decision to have exactly two roles, to split role-checks (403) from
   state-checks (409) via per-method `@PreAuthorize` rather than a class-level
   annotation or a service-layer check, is a distinct architectural choice
   (explained in `InvoiceController.java:20-29`, `TeamController.java:20-22`,
   `PaymentController.java:18-23`) with no ADR of its own.

4. **Timezone hardcoded as `Asia/Singapore`** —
   `src/main/java/com/invoicely/domain/BusinessCalendar.java` (per
   `README.md:117-121`) pins the timezone in code rather than via
   configuration, for a stated correctness reason. This is a decision with a
   real trade-off (single-region assumption baked into the domain) and is
   called out in the README but has no ADR.

---

## 3. knowledge/ — drift vs actual code

Compared `knowledge/domain/{business-and-roles,invoice-lifecycle,
invoice-numbering,money}.md` against `InvoiceStatus.java`,
`InvoiceLifecycleService`/`InvoiceController`, `InvoiceNumbering.java`,
`InvoiceTotals.java`, `PaymentService.java`, `AccountStateFilter.java`, and
`Business.java`.

**No drift found.** Specifically verified and confirmed accurate:
- The full state-transition matrix in `invoice-lifecycle.md` matches
  `InvoiceStatus.ALLOWED` exactly (`InvoiceStatus.java:52-57`), including the
  409-vs-403 split.
- `money.md`'s formulas (`lineTotal`, `subtotal`, `gst`, `invoiceTotal`,
  `balance`) and its GST-snapshot description match `InvoiceTotals.of()` and
  `applicableGstRate()` line for line (`InvoiceTotals.java:40-77`), and the
  default GST rate (9%, `new BigDecimal("0.0900")`) matches
  `Business.java:39`.
- `invoice-numbering.md`'s claims (per-business, per-year, zero-padded to 4
  digits, `9,999` cap, row-lock allocation) match `InvoiceNumbering.java`
  exactly, including the `SEQUENCE_LIMIT = 10_000` boundary check.
- `business-and-roles.md`'s capability table and "cross-business is 404, role
  violation is 403" claims match the actual `@PreAuthorize` placement and
  `GlobalExceptionHandler`'s `AuthorizationDeniedException` handler.

This is a genuinely well-maintained knowledge base for a codebase this size;
no action needed here beyond normal update discipline going forward.

---

## 4. OpenAPI annotations vs actual controller behavior

**Finding: there are no per-endpoint OpenAPI annotations anywhere in the
codebase.** A repo-wide search for `@Operation`, `@ApiResponse`, `@Schema`,
and `@Tag` across `src/main/java` returns zero matches in any controller —
the only OpenAPI-related code is `src/main/java/com/invoicely/OpenApiConfig.java`,
which sets a global title/description and the bearer security scheme, nothing
endpoint-specific.

This is not a *contradiction* (nothing false is asserted), but it is a real
gap worth flagging: springdoc will auto-generate the schema shapes and HTTP
methods from Spring MVC + Bean Validation annotations, but Swagger UI shows no
per-endpoint description of the 403/404/409/400 Problem Details responses that
are actually one of this codebase's strongest, most deliberately-designed
features (three separate, tested failure axes — see
`GlobalExceptionHandler.java:19-58`, `InvoiceStatus.java:14-27`). A recruiter
or API consumer browsing `/swagger-ui.html` would see plain 200-only
operations and miss that design entirely. `docs/Invoice_Tech_Scope.md:140`
scoped Task 6's OpenAPI work as just "Swagger" (get the UI running), so this
isn't a broken promise — but it is the one clear opportunity in this area.

The one manual doc that does exist (`OpenApiConfig.java`'s `Info.description`)
was checked against behavior and is accurate: it correctly describes the two
roles, the maker-checker lifecycle, and the 403/409/404 error-axis split.

---

## 5. Javadoc / comment rot

Spot-checked: `InvoiceController`, `ClientController`, `ClientService`,
`PaymentService`, `InvoiceStatus`, `OverdueInvoices`, `InvoiceNumbering`,
`InvoiceTotals`, `GlobalExceptionHandler`, `AccountStateFilter`,
`TeamController`, `SettingsController`. A repo-wide search for `TODO`,
`FIXME`, and `XXX` in `src/main/java` returned zero matches.

**No contradictions found** in the sampled files — every Javadoc claim checked
(rounding order, GST-snapshot fallback, overdue boundary condition, role vs.
state axis, account-deactivation behavior, invoice-numbering cap) matched the
code it documents. This was a spot check, not an exhaustive line-by-line
review of every file in `src/main/java`, so treat "0 found" as "0 found in
the sampled ~12 files," not a guarantee for the remaining ones (DTOs,
repositories, and `SecurityConfig`/`JwtService` were not individually
line-audited).

---

## Ranked fix list

1. **Write the four missing ADRs** (money/GST-snapshot handling, the
   dual-mechanism overdue design, the OWNER/STAFF maker-checker role model,
   and the hardcoded Singapore timezone). This is the highest-impact gap: it's
   a violation of the project's own stated rule (`CLAUDE.md`), it affects
   exactly the decisions the README already spotlights as noteworthy, and
   skipping it is what will most confuse the owner later when she can't find
   the "why" for a decision the README promises is written up elsewhere.

2. **Add per-endpoint OpenAPI annotations** (`@Operation` summaries,
   `@ApiResponse` for 400/403/404/409 per lifecycle/payment endpoint). This is
   the single most recruiter-visible gap — anyone who clicks through to
   `/swagger-ui.html` (which the README explicitly invites them to do) sees a
   flat, undocumented API surface that undersells the maker-checker/error-axis
   design that's actually the strongest part of this codebase.

3. **Document the query parameters for `GET /clients` and `GET /invoices`
   in the README table** (archived/q/page/size; status/clientId/page/size).
   Cheap fix, meaningfully improves a skim-reader's understanding of what the
   API can actually do.

4. **Add a CI badge to README.md.** Trivial, high-visibility polish for
   recruiters landing on the repo's front page.

5. **Add `reports/` to `FILE-MAP.md`.** Lowest priority — cosmetic, but it's
   the one file whose entire job is to stay current.
