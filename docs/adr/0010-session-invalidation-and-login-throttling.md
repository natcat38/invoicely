# ADR-0010 — A password change invalidates older tokens; login is throttled per IP

**Status:** accepted · **Date:** 2026-09-06 · **Decided by:** Natalie

## Context

The Phase 1 hardening audit (`reports/phase1-audit/security-review.md`) left
three authentication questions open because each one changes behaviour a user
can see, and behaviour is the owner's call. Phase 2 builds the UI that has to
implement whatever was decided, so they had to be answered before Task 7.

1. **A password change did not invalidate anything.** ADR-0002 chose stateless
   bearer tokens with no refresh token and no server-side session store. That
   is what makes the API simple, but it also means there is nothing to revoke:
   a token stolen on Monday keeps working until it expires, and changing the
   password on Tuesday does not stop it. "Change your password" is the one
   action a user takes precisely *because* they think someone else has their
   credentials, so it is the one place the gap actually matters.

2. **`/auth/login` and `/auth/register` had no rate limiting.** Nothing slowed
   an attacker down between guesses. BCrypt makes each guess cost tens of
   milliseconds, which is a real brake but not a limit.

3. **A deactivated account's request answered 401.** Arguably wrong: the token
   is genuine and the caller is exactly who they claim to be. The failure is
   authorisation, not authentication, which is a 403.

## Decision

**(1) Reject tokens issued before the account's last password change.**
`users.password_changed_at` (V2) is written by `AuthService.changePassword` and
by nothing else. `AccountStateFilter` compares it against the token's `iat`
claim and answers `401 token-superseded` when the token is older.

Null means "never changed", so every existing account and every freshly
registered one is unaffected until its first password change.

**(2) A fixed-window, in-memory throttle on the two open endpoints.** Ten
failed attempts from one IP address in fifteen minutes, then `429` with a
`Retry-After` header until the window rolls over. Only failures count, so a
busy office behind one NAT address is not punished for logging in successfully.

**(3) A deactivated account gets `403 account-deactivated`.**

## Consequences

- **Changing a password logs out every other session, but not the current one.**
  `changePassword` already issued a fresh token in its response, and that token
  is stamped after `password_changed_at`, so the caller continues uninterrupted.
  This matters for Phase 2's forced-change interstitial: a staff member setting
  their first real password stays signed in and lands in the app. No re-login
  screen is needed.

- **`iat` has one-second resolution, so invalidation has a sub-second seam.**
  A JWT's `iat` is whole epoch seconds, while `password_changed_at` is a
  microsecond-precision `timestamptz`. `password_changed_at` is therefore
  truncated to the second before comparison, and only a *strictly* older token
  is rejected. A token issued in the same second as the password change
  survives. Closing that seam would mean either storing seconds (losing
  precision permanently) or reading a custom high-resolution claim, and the
  window it leaves is one second of an attack that requires already holding a
  stolen token. Named here so it is a known limit rather than a lurking bug.

- **The throttle is per instance and is lost on restart.** It is a
  `ConcurrentHashMap`, not Redis. Two instances would each allow ten attempts,
  and a restart forgives everyone. That is the honest limit of a
  no-new-dependency solution, and it is stated in the README's production notes
  rather than hidden. If the demo is ever deployed to more than one instance,
  the upgrade path is a shared store behind the same `LoginThrottle` interface,
  not a rewrite of the filter.

- **Per-IP, not per-account, and deliberately.** An account-based lockout hands
  an attacker a way to lock a real user out of their own business by guessing
  wrong on purpose. Per-IP throttling has the opposite failure mode — several
  users behind one address share a budget — which is the one worth having when
  the budget only counts failures.

- **The UI treats 401 and 403 differently, so 403 for deactivation costs a
  branch.** `401` means "the token is no good, clear it and go to login", which
  now covers expiry and `token-superseded`. `403` means "you are signed in and
  may not do this" — ordinarily a role failure that shows an inline message.
  Deactivation is a 403 that must *not* stay on the page, so Phase 2's HTTP
  client keys on the problem `type` (`/problems/account-deactivated`) rather
  than the status alone. The `type` field exists for exactly this;
  distinguishing failures by status code alone is what it was meant to replace.

## Alternatives rejected

- **A token denylist or a server-side session table.** Both work and both undo
  ADR-0002's central trade: the API would need a store hit on every request.
  The `password_changed_at` check needs no extra query at all —
  `AccountStateFilter` already loads the user row on every authenticated
  request to check `active` and `must_change_password`.

- **A `jti` claim with revocation.** Same cost, and it solves a bigger problem
  (revoking one arbitrary token) than this product has.

- **Bucket4j or Spring Cloud Gateway's rate limiter.** A dependency and a
  configuration surface for roughly thirty lines of `ConcurrentHashMap`.

- **Doing nothing about rate limiting** and noting it as a production gap. This
  was the recommendation on the grounds that the demo holds no real data; the
  owner chose to build it, on the grounds that an authentication endpoint with
  no brake is the kind of thing a reviewer looks for.
