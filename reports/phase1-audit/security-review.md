# Invoicely — Phase 1 Security Review

**Scope:** Spring Boot API, Tasks 1–6 (merged to `main` as of commit `10b78a4`).
**Method:** Manual read-through of every class in `src/main/java/com/invoicely`
(security, web, domain packages), `application.properties`, `pom.xml`,
`compose.yaml`, `.gitignore`, and `docs/Invoice_Product_Scope.md` /
`docs/Invoice_Tech_Scope.md` for intended behaviour. No files were modified.
Dependency versions were read from `pom.xml`; no live CVE database lookup was
performed (see note at the end).

Overall impression: this is a carefully built codebase. The ownership
boundary (`business_id` from the JWT) is enforced by a single consistent
pattern — every entity lookup goes through a `findByIdAndBusinessId` (or the
business itself, loaded by an id that *is* the JWT claim) — applied uniformly
across clients, invoices, payments, team members, and settings. I checked
every controller and repository for a bypass of that pattern and found none.
The findings below are genuine, but they are refinements to an already-sound
design, not structural failures.

## Findings summary

| Severity | Count |
|----------|-------|
| Critical | 0 |
| High     | 0 |
| Medium   | 2 |
| Low      | 4 |
| Informational (clean areas / notes) | 6 |

---

## Medium

### M1 — No rate limiting or lockout on `/auth/login` and `/auth/register`

**Where:** `src/main/java/com/invoicely/web/AuthController.java` (both
endpoints are `permitAll` in `SecurityConfig.java:68`), `AuthService.login`
(`AuthService.java:101-111`).

**Issue:** Nothing in the filter chain, `AuthService`, or a bean anywhere in
the app limits the rate of login or registration attempts per IP, per email,
or globally. BCrypt (`BCryptPasswordEncoder()`, default strength 10,
`SecurityConfig.java:152`) makes each guess computationally expensive, which
helps, but does not stop:

- Credential stuffing / distributed password guessing against `/auth/login`
  (an attacker with a leaked email/password list from another breach can
  test it here at whatever concurrency their infrastructure allows).
- Automated bulk account/business creation via `/auth/register`, since it is
  `permitAll` and unthrottled.

**Exploit scenario:** An attacker scripts requests to `POST /auth/login`
against a known owner email, trying a common-password list. There is no
5xx/429 backoff, no lockout after N failures, no CAPTCHA. Given enough time
or a distributed source, the attacker eventually finds an owner account.

**Fix:** Add rate limiting at the edge (reverse proxy / API gateway) or in
the application (e.g. a Bucket4j filter keyed on IP + email for
`/auth/login`, and IP-based limiting on `/auth/register`). At minimum, log
and alert on repeated failures. This is not called out in the Tech Scope as
an explicit task, so it should be raised as an owner decision: accept the
risk for Phase 1 (small trusted user base) or add a lightweight limiter now.

### M2 — Password change does not invalidate previously issued tokens

**Where:** `AuthService.changePassword` (`AuthService.java:119-132`),
`AccountStateFilter.doFilterInternal` (`AccountStateFilter.java:65-90`).

**Issue:** `AccountStateFilter` re-checks `active` and `mustChangePassword`
from the database on every request (by design, per its own Javadoc), which
correctly makes deactivation bite immediately. It does **not** check
anything that would let a password change invalidate tokens issued *before*
that change. Since there are no refresh tokens and no server-side session
(ADR-0002, stateless JWT), a token issued at login remains valid for its
full lifetime (`invoicely.security.token-lifetime`, default 12h,
`SecurityProperties.java:22`) even after the account holder changes their
password because they suspected compromise.

**Exploit scenario:** An owner's laptop is left unlocked and their bearer
token is copied by someone else (e.g. via browser dev tools or a proxy log).
The owner notices and changes their password immediately. The copied token
is still fully valid for up to 12 hours — changing the password did nothing
to stop the person who already has the token.

**Fix:** Either (a) add a `credentials_changed_at` timestamp on `User` and
have `AccountStateFilter` compare it against the token's `iat` claim,
rejecting tokens issued before the last password change, or (b) shorten
`token-lifetime` and accept the residual window as a documented trade-off.
Option (a) is a small, surgical addition to the same filter that already
does a per-request DB read, so it doesn't add a new query.

---

## Low

### L1 — Cross-tenant email enumeration via `/team` and `/auth/register`

**Where:** `TeamService.create` (`TeamService.java:118-124`),
`AuthService.register` (`AuthService.java:70-73`),
`UserRepository.existsByEmailIgnoreCase` (global, not business-scoped, by
design — `UserRepository.java:34`).

**Issue:** Email uniqueness is intentionally global (documented, and needed
so one person can't hold two accounts under different businesses). But this
means a `409 email-taken` response leaks whether an email address has *any*
account on the platform, to a caller who has no other relationship to that
email's business. Specifically: an OWNER of Business A can call
`POST /team` with a staff member's or a competitor owner's email address
and learn from the 409 whether that email is already registered somewhere
on Invoicely — information they have no legitimate need to know about an
account outside their own business.

**Exploit scenario:** Low-value in isolation (it only confirms platform
membership, not which business), but it is exactly the kind of enumeration
oracle that compounds with other leaks (e.g. combined with a known company
domain, an attacker could map which staff at a target company use
Invoicely).

**Fix:** Either accept this as a documented trade-off of the global-email
design (it is a deliberate, ADR-worthy decision already), or change `/team`
create to return a generic "invitation sent" style response regardless of
whether the email existed, and let the actual failure surface only to the
owner's own later attempts to look up that user. Given the current UX
(temporary password shown once to the owner), full suppression isn't free —
this is a product decision, flagged for a call, not a code fix.

### L2 — Login timing difference between "unknown email" and "wrong password"

**Where:** `AuthService.login` (`AuthService.java:102-111`).

```java
User user = users.findByEmailIgnoreCase(request.email())
        .filter(User::isActive)
        .orElseThrow(AuthService::invalidCredentials);   // <-- returns before any BCrypt call

if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
    throw invalidCredentials();
}
```

**Issue:** Both branches return the identical `401 invalid-credentials`
body — that part is correct and deliberate (see the Javadoc). But the two
paths take measurably different time: an unknown or inactive email returns
immediately, while a known email always pays the BCrypt verification cost
(tens of milliseconds by design). This is a classic timing side channel for
account enumeration, independent of the response body being identical.

**Exploit scenario:** An attacker measures response latency across a list
of candidate emails against `/auth/login` with a fixed wrong password.
Requests that take noticeably longer indicate a registered, active email.

**Fix:** Run a dummy `passwordEncoder.matches` against a constant, fake hash
when the user isn't found (or is inactive), so both branches always pay the
same BCrypt cost. Small change, same file.

### L3 — No CORS configuration (currently blocks the future Phase 2 frontend by default; flagging so a wildcard isn't reached for as the "quick fix" later)

**Where:** `SecurityConfig.java` — no `.cors(...)` customizer, no
`CorsConfigurationSource` bean anywhere in the codebase.

**Issue:** Not a vulnerability today — with no CORS configuration, Spring
Security applies none, so browsers enforce same-origin by default and no
cross-origin caller is specifically allowed. This is safe as-is. Flagging it
now because Phase 2 (per `docs/Invoice_Design_Direction.md`) will need a
frontend origin allow-listed, and the common mistake at that point is
`allowedOrigins("*")` combined with `allowCredentials(true)` (which Spring
actually rejects at startup, but `allowedOriginPatterns("*")` with
credentials is *not* rejected and is equally unsafe with a bearer-token
API). Since tokens here travel in an `Authorization` header rather than a
cookie, credentialed CORS isn't even needed — the eventual config should use
an explicit origin allow-list and `allowCredentials(false)`.

**Fix:** No action needed now. When Phase 2 adds a CORS config, allow-list
the exact frontend origin(s) per environment; do not use a wildcard.

### L4 — `invoicely.security.secret` has no format/rotation guidance beyond length

**Where:** `SecurityConfig.jwtSigningKey` (`SecurityConfig.java:112-133`).

**Issue:** The 32-character minimum is enforced (good — prevents a
trivially brute-forceable HMAC key), but nothing checks the *quality* of the
configured secret. A 32-character low-entropy string (e.g. a repeated
word) passes the length check but is weak as an HMAC-SHA256 key. This is a
deployment-configuration risk, not a code defect — the code cannot enforce
entropy it never receives.

**Fix:** Document (e.g. in the README's deployment section or a
`SECURITY.md`) that `INVOICELY_SECURITY_SECRET` must be a high-entropy
random value (e.g. `openssl rand -base64 32`), not a memorised phrase.
Purely a documentation gap — worth closing before the first real deploy.

---

## Explicitly checked and clean

- **Tenant boundary (`business_id`), every endpoint and repository query.**
  `ClientRepository`, `InvoiceRepository`, `UserRepository`,
  `BusinessRepository` — every single-row lookup used by a controller-facing
  service goes through `findByIdAndBusinessId` or loads the `Business` row
  itself by the id taken from the JWT (`CurrentRequest.businessId()`, the
  *only* place a business id is read from the token —
  `CurrentRequest.java:30-36`). Verified for: `ClientService` (create,
  get, list, update, delete), `InvoiceService` (create, get, list, update,
  delete), `InvoiceLifecycleService` (submit/send/reject), `PaymentService`
  (record/list), `TeamService` (list/create/setActive), `SettingsService`
  (get/update), `DashboardService`. No endpoint accepts a business id from
  the request body or a query parameter, and no repository method exposes an
  unscoped `findById` that a controller path calls with a caller-supplied id.
  Cross-business access correctly 404s (`NotFoundException`), never 403,
  matching ADR-0001.
- **Role enforcement.** `@PreAuthorize("hasRole('OWNER')")` is present on
  every owner-only endpoint (`TeamController`, `PaymentController`,
  `DashboardController`, `SettingsController`, and the `send`/`reject`
  lifecycle endpoints on `InvoiceController`), and
  `GlobalExceptionHandler.handleAuthorizationDenied` turns a role failure
  into a 403 Problem Details response distinct from the 404 (tenancy) and
  409 (state) cases. The role claim can't be forged from the request body —
  it's read only from the verified JWT.
- **JWT signing/verification.** HS256 with an explicit `macAlgorithm`
  constraint on the decoder (`NimbusJwtDecoder...macAlgorithm(MacAlgorithm.HS256)`,
  `SecurityConfig.java:141-145`) — immune to the classic "alg: none" /
  algorithm-confusion attack, since the decoder won't accept any other
  algorithm regardless of what the token header claims. Expiry
  (`expiresAt`) is set and enforced by Spring's resource-server support.
  Claims are minimal (`sub`, `biz`, `role`) and nothing mutable (email,
  active flag, must-change-password) is trusted from the token — those are
  re-read from the DB every request, which is the correct design for making
  deactivation bite immediately.
- **Password storage.** BCrypt via `BCryptPasswordEncoder()`
  (`SecurityConfig.java:151-154`), default work factor (≥10, OWASP-adequate).
  Byte-length capped at 72 before hashing to avoid BCrypt's silent
  truncation/exception behaviour (`AuthService.hash`,
  `AuthService.java:138-144`). No composition rules forced (NIST-aligned,
  documented rationale). Temporary passwords for new staff
  (`TemporaryPasswords.java`) are generated with `SecureRandom`, from a
  57-character alphabet, 12 characters long, shown to the owner exactly
  once and never logged or persisted in plaintext.
- **Mass assignment.** Every request DTO (`InvoiceRequest`, `ClientRequest`,
  `RegisterRequest`, `CreateStaffRequest`, `SettingsRequest`,
  `RecordPaymentRequest`, etc.) is a narrow record exposing only the fields a
  caller should set. None accept a business id, user id, role, invoice
  number, computed total, or status — all of those are server-derived. I
  found no endpoint that binds a request directly onto a JPA entity.
- **Injection.** All queries are JPQL via Spring Data (`@Query` with named
  parameters, or derived query methods) — no string concatenation of
  request data into a query, and no native/raw SQL anywhere in
  `src/main/java`. `ClientService.list`'s search-term handling
  (lower-cased, wrapped in `%...%` in Java before being bound as a parameter,
  never concatenated into the JPQL string) is explicitly documented and
  correctly implemented — it avoids SQL injection and the Postgres
  parameter-type inference issue it exists to solve.
- **Secrets in the repo.** No `.env`, `.pem`, key file, or hardcoded
  production credential found. `compose.yaml`'s Postgres credentials
  (`invoicely`/`invoicely`) are local-dev-only and clearly commented as
  such; `application.properties` leaves `invoicely.security.secret` unset
  on purpose so a real secret can never be committed, and fails loudly
  (`IllegalStateException`) if a configured secret is under 32 bytes.
  `.gitignore` excludes `.env` and `*.local`.

---

## Not independently verified (flagging rather than guessing)

- **Dependency CVEs.** `pom.xml` pins `spring-boot-starter-parent` 4.1.1 and
  `springdoc-openapi-starter-webmvc-ui` 3.1.0. Per the "API/Service Errors"
  house rule, I did not attempt a live vulnerability-database lookup as part
  of this review (that needs an actual OSV/NVD/GitHub-advisories query
  against exact resolved versions, not something safely inferred from
  training data for versions this recent). Recommend running
  `mvn versions:display-dependency-updates` plus a proper SCA tool
  (OWASP Dependency-Check, `mvn org.owasp:dependency-check-maven:check`, or
  GitHub Dependabot alerts, which may already be active on this repo) as a
  follow-up rather than trusting this review's read of version numbers.
- **Actuator exposure.** `spring-boot-starter-actuator` is on the classpath
  but `management.endpoints.web.exposure.include` is not set anywhere, so
  Spring Boot's default (only `/actuator/health`) applies, and even that
  path is not in the `permitAll` list in `SecurityConfig`, so it currently
  requires a valid bearer token like everything else. This looked correct
  on inspection, but was not exercised against a running instance in this
  review (no code was executed, per the review's own constraint).

---

## Recommended priority order

1. M1 (auth rate limiting) — decide risk acceptance vs. add a limiter before
   any public-facing deploy.
2. M2 (token invalidation on password change) — small, contained fix.
2. L2 (login timing) — trivial fix, same file as M2's neighbourhood.
4. L1 (cross-tenant email enumeration) — owner decision, not purely
   technical.
5. L3/L4 — documentation and Phase 2 planning notes, no code change needed
   now.
