# Task 7a — auth hardening report

Implemented per ADR-0010 across all files in the assigned list:

- `User.passwordChangedAt` (nullable `Instant`), set only by
  `AuthService.changePassword` alongside the existing hash/flag update.
- `AccountStateFilter`: deactivated → `403 account-deactivated` (was 401);
  new `token-superseded` `401` check (iat vs. `passwordChangedAt` truncated to
  seconds, strictly-before); restructured to fetch the `Jwt` once at the top
  via a new `currentToken()`/`userFor()` pair (no new type introduced).
- `LoginThrottleFilter` (new): fixed 10-failures/15-min window per
  `getRemoteAddr()`, `ConcurrentHashMap<String, Window>` with a 10,000-entry
  cap swept of expired entries before growing, `429 too-many-attempts` +
  `Retry-After` when already over limit, `shouldNotFilter` scoped to
  `POST /auth/login|register`.
- `SecurityConfig`: registered the throttle filter
  (`addFilterBefore(..., BasicAuthenticationFilter.class)`); added
  `.cors(Customizer.withDefaults())`, a `CorsConfigurationSource` bean reading
  `SecurityProperties.allowedOrigins()` (no `allowCredentials`), and
  `CorsUtils::isPreFlightRequest` permitted so OPTIONS never needs a token.
- `SecurityProperties`: added `allowedOrigins`, defaulting to
  `http://localhost:5173` when null/empty.
- `GET /auth/me` + `MeResponse` (new record, no token/expiry fields) +
  `AuthService.me()` (`@Transactional(readOnly = true)`, same
  `findByIdAndBusinessId` pattern as `changePassword`).
- `application.properties`: commented `invoicely.security.allowed-origins`
  block in the existing voice.
- Tests added/updated in `AuthApiTest` (token-superseded rejection, null
  `passwordChangedAt` still works, session-surviving change-password, three
  `/auth/me` cases) and the deactivation-replay test now expects `403`. New
  `LoginThrottleFilterTest` (11th failure → 429 + Retry-After; success clears
  counter), each test on its own IP since the throttle's map is a
  singleton-bean `ConcurrentHashMap` `@Transactional` cannot reset.

## Deviation from the spec, and why

The "token issued before a password change is rejected, and the token
change-password returns still works" item was split into **two** tests
instead of one:

1. `tokenIssuedBeforePasswordChangeIsRejected` — sets `passwordChangedAt`
   directly on the row, two seconds in the future, rather than deriving it
   from a real `change-password` call.
2. `changingPasswordDoesNotLogOutTheCurrentSession` — calls the real
   endpoint and asserts the fresh token works.

Reason: if both assertions came from one real `change-password` call, the
"old token now rejected" assertion would race the wall clock — the old
token's `iat` and the new `passwordChangedAt` can legitimately land in the
same second (that's the deliberate one-second seam ADR-0010 names), which
would make the test intermittently fail for a reason that has nothing to do
with a code defect. Setting the timestamp directly removes that race while
still exercising the real `AccountStateFilter` comparison logic end to end.

Nothing else required changes outside the assigned file list. No build/tests
were run per the hard rules — Java/Spring syntax was checked by reading
neighbouring files and existing tests for conventions (Boot 4 test-annotation
packages, Jackson 3 `tools.jackson.databind.ObjectMapper`, etc.) but has not
been compiled.
