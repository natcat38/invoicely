# Phase 1 security audit — auth, team, settings, JWT

Scope: SecurityConfig, AccountStateFilter, JwtService, SecurityProperties,
TemporaryPasswords, AuthController/Service, CurrentRequest,
GlobalExceptionHandler, ApiException family, TeamController/Service,
SettingsController/Service + DTOs, User/Role/UserRepository,
Business/BusinessRepository. Tests skimmed: AuthApiTest, TeamApiTest,
SettingsApiTest, TestTokens.

## Findings

### [MINOR] No regression test proves a live token dies on deactivation
- File: src/test/java/com/invoicely/web/TeamApiTest.java, src/test/java/com/invoicely/web/AuthApiTest.java
- Scenario: The single most safety-critical promise in this slice — "deactivation
  must bite on the next request, not just at login" — is implemented in
  AccountStateFilter (src/main/java/com/invoicely/security/AccountStateFilter.java:79-83)
  but no test issues a token, deactivates that same user (e.g. via
  `PATCH /team/{id}` or directly through the repository), and then replays the
  *original, still-unexpired* token against a protected endpoint to confirm it
  is now rejected. `deactivatedUserCannotLogIn` in AuthApiTest only proves the
  login path rejects a deactivated account, which is a different code path
  (AuthService.login's `.filter(User::isActive)`) from the one the invariant is
  actually about (AccountStateFilter re-checking `active` per request on an
  already-issued token). If a future refactor accidentally moved the active
  check to only run at token-issue time, no test would catch it.
- Fix: Add a test that: issue a token for an active staff user, deactivate them
  (via UserRepository or the PATCH endpoint with a second owner token), then
  reuse the original token on `GET /clients` (or similar) and assert 401
  `account-deactivated`.

### [MINOR] Deactivated-account status code (401) vs. arguably-more-correct 403
- File: src/main/java/com/invoicely/security/AccountStateFilter.java:79-83
- Scenario: A deactivated user's request is answered 401 ("account-deactivated"),
  even though the bearer token is well-formed, signed, and unexpired — the
  caller *is* authenticated, they are simply no longer permitted to act. 403
  would be the more conventional code for "identity known, action refused" (the
  same reasoning GlobalExceptionHandler documents for the role-vs-state-vs-tenant
  409/403/404 split). This is a documented, deliberate choice (see the class
  Javadoc) and every test agrees with it, so it is not a bug — flagging only
  because it's a defensible-but-debatable call worth the owner confirming
  matches the Product Scope's intent, since a front-end that branches on status
  code (rather than the `type` field) could treat this as "log out and show
  login" instead of "show a deactivated-account message."
- Fix: None required if the owner confirms 401 is intentional; otherwise change
  to 403 and update the one test (`AuthApiTest` has no coverage of this path
  directly — only `TeamApiTest`/manual testing would need adjusting).

## Rejected / verified-correct

- Every `business_id` scoped query in the reviewed files goes through
  `findByIdAndBusinessId` (TeamService.load, AuthService.changePassword) or
  derives the id itself from `CurrentRequest.businessId()`
  (SettingsService.load, TeamService.create) — never from a path/body param.
  Confirmed no controller/DTO in `web/` accepts `businessId` or a raw `userId`
  from the client (grepped all of `src/main/java/com/invoicely/web`).
- Cross-business access returns 404 (`NotFoundException`), never 403 —
  `TeamApiTest.patchOnAnotherBusinessesUserIs404` proves this for `/team`, and
  `NotFoundException`'s Javadoc explicitly forbids ever distinguishing
  "doesn't exist" from "not yours."
- Role violations correctly return 403 via `@PreAuthorize("hasRole('OWNER')")`
  on every owner-only endpoint I reviewed: `TeamController` (list/create/setActive)
  and `SettingsController` (get/update), each annotated per-method rather than
  at class level (deliberately, so a moved/renamed method can't silently lose
  its guard) — confirmed present on all four/two methods respectively, and
  `SettingsApiTest.staffAreLockedOut` / `TeamApiTest.staffIsForbiddenOnEveryEndpoint`
  both exercise it end to end.
- `must_change_password` bypass: `AccountStateFilter` blocks every path except
  a `PathPatternRequestMatcher`-based match on `POST /auth/change-password`
  (method + path, not a raw `getRequestURI()` string compare, so it survives a
  context-path deployment) — and `AuthApiTest.mustChangePasswordBlocksEverythingExceptChangePassword`
  proves it end to end against a real filter chain.
- Deactivation is re-checked from the database on every authenticated request
  (`AccountStateFilter.currentUser()` → `UserRepository.findById`), not read
  from the JWT — the token deliberately carries no `active` claim
  (`JwtService`'s Javadoc states this explicitly), so a stale token cannot
  resurrect a deactivated account. (See the MINOR finding above about the one
  missing test that would prove this by replaying an old token.)
- `AccountStateFilter` is registered via
  `addFilterAfter(accountStateFilter, BasicAuthenticationFilter.class)`. Spring
  Security's default filter ordering places `BearerTokenAuthenticationFilter`
  (order ~21) before `BasicAuthenticationFilter` (order ~22), so by the time
  this filter runs the JWT has already been verified and
  `SecurityContextHolder` is populated — confirmed empirically by every
  `AccountStateFilter`-dependent test (`mustChangePasswordBlocksEverythingExceptChangePassword`,
  the deactivation tests) actually passing against the real filter chain via
  `@AutoConfigureMockMvc`.
- JWT algorithm is pinned: `NimbusJwtDecoder.withSecretKey(signingKey).macAlgorithm(MacAlgorithm.HS256)`
  in SecurityConfig explicitly restricts verification to HS256 — no "alg: none"
  or RS256-confused-as-HS256-public-key attack surface, since the decoder never
  looks at an `alg` header supplied by the token to choose its verification
  strategy.
- JWT signing key: HS256 requires ≥256 bits and `SecurityConfig.jwtSigningKey`
  enforces that (`keyBytes.length * 8 < KEY_BITS` → `IllegalStateException`) for
  a configured secret, and generates a proper random 256-bit key via
  `KeyGenerator`/`SecureRandom` when none is configured, with a loud `log.warn`
  that tokens won't survive a restart and multi-instance signing will disagree.
  This is a documented, deliberate dev-convenience trade-off, not an oversight
  — not flagging as a finding since it explicitly tells an operator to set the
  property outside development and fails loudly (short-lived, single-instance
  local dev only) rather than silently.
- Token expiry: every token carries `issuedAt`/`expiresAt` (`JwtService.issue`),
  and there are no refresh tokens (by design, ADR-0002), so a compromised token
  self-expires within `tokenLifetime` (12h default) with no renewal path to
  extend that window.
- Password hashing: `BCryptPasswordEncoder` at default strength (work factor
  10) for both real passwords and the generated temporary password
  (`TeamService.create` hashes via the same `PasswordEncoder`, never stores
  plaintext) — confirmed by `TeamApiTest.temporaryPasswordIsHashedNotStored`.
  `PasswordEncoder.matches()` is BCrypt's own constant-time comparison — no
  custom `.equals()` timing-attack surface anywhere in the reviewed code.
- Password-length ceiling: `AuthService.hash` rejects (400, not 500) any
  password whose UTF-8 byte length exceeds BCrypt's 72-byte hard limit, and
  does so by byte length rather than character count (so accented/emoji
  passwords under 72 *characters* but over 72 *bytes* are still caught) —
  proven by `AuthApiTest.overlongPasswordsAreRejectedCleanly`.
- Temporary password entropy: 12 characters from a 57-character alphabet
  (`~2^70`) generated with `java.security.SecureRandom` (a CSPRNG, not
  `java.util.Random`), with visually-confusable characters (`0/O`, `1/l/I`)
  deliberately excluded for a different reason (operator legibility) that
  doesn't reduce the security-relevant entropy meaningfully. Never logged:
  grepped the temp-password path (`TemporaryPasswords.generate` →
  `TeamService.create` → `CreatedStaffResponse`) and found no `log.*` call
  anywhere it flows through; it is returned exactly once in the HTTP response
  body and nowhere else.
- Login information leakage: unknown email and wrong password return
  byte-for-byte identical 401 bodies (`AuthService.login`, proven by
  `AuthApiTest.loginFailsIdenticallyWhetherTheEmailIsUnknownOrThePasswordIsWrong`),
  and a deactivated account fails login the same way for the same
  stated reason (not distinguishing "exists but deactivated" from "wrong
  password/unknown email").
- `GlobalExceptionHandler` never echoes exception internals: `ApiException`
  responses use only the exception's own curated `type`/`detail`;
  `AuthorizationDeniedException` gets a fixed, generic message
  ("Your role does not allow this.") rather than Spring Security's own denial
  detail; `MethodArgumentNotValidException` handling emits only field name +
  Bean Validation message, never a stack trace or SQL. No uncaught-exception
  handler is defined in this class, so anything else falls through to Spring
  Boot's default error handling, which defaults `server.error.include-stacktrace`
  and `include-message` to `never` — confirmed no `application.yml`/`.properties`
  override in `src/main/resources` flips either to `always`.
- Ownership boundary for `/settings` and `/team`: both always resolve the
  target row from `CurrentRequest.businessId()` (JWT-derived), never from a
  path variable — `/settings` has no id in its path at all by design, and
  `/team/{id}` scopes the id through `findByIdAndBusinessId` before touching it.
- `TeamService.setActive` correctly blocks an owner from deactivating their own
  account (`ConflictException("cannot-deactivate-self")`, 409 — the state axis,
  not a role or tenant failure), preventing a business from being locked out of
  every owner-only action; proven by `TeamApiTest.ownerCannotDeactivateSelf`.
- `role` claim in the JWT is only ever used by Spring Security's
  `JwtGrantedAuthoritiesConverter` (via `@PreAuthorize`) for actual
  authorization decisions; `CurrentRequest.role()` is documented and used only
  to *describe* the caller (e.g. UI hints), never to gate an action itself.
