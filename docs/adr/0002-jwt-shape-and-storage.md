# ADR-0002 — Short-lived HS256 bearer tokens carrying three claims, no refresh token

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

Task 4 replaces the permit-all stub with real authentication. Three questions
had to be answered together, because the answers constrain each other: what goes
in the token, how it is signed, and how the browser holds it.

The Tech Scope already fixed the stack — Spring Security's OAuth2 Resource
Server, which expects a bearer token. That rules out server-side sessions, and
it is the right call for an API that a separate React app will consume.

## Decision

### Shape — three claims and nothing else

```
sub  = user id
biz  = business id      (the ownership boundary, ADR-0001)
role = OWNER | STAFF    (mapped to a ROLE_ authority for @PreAuthorize)
```

Nothing that can change between logins goes in the token. Specifically **not**
`active` and **not** `must_change_password`, even though both would be
convenient there. The Tech Scope requires that deactivating a staff member takes
effect on their *next request*, not their next login, and a claim baked into a
12-hour token cannot do that. Both flags are read from the database on every
authenticated request by `AccountStateFilter`.

Nor does the token carry the email or the business name. They are display data;
putting them in a signed credential means they go stale the moment either is
edited.

### Signing — HS256 with a symmetric secret

One application issues these tokens and the same application verifies them.
Asymmetric keys exist so that a verifier can check a signature it could not have
produced — there is no such party here, so RSA would add key management for a
property nobody needs.

If `invoicely.security.secret` is unset, a random key is generated at startup.
Local development therefore needs no setup, and no secret is ever committed to
this repository. The cost is that tokens do not survive a restart and two
instances would not accept each other's — so any real environment must set it.
A startup warning says so.

### Lifetime — 12 hours, no refresh token

Long enough for a working day, so nobody is logged out mid-invoice. Refresh
tokens exist to keep access tokens short while sessions stay long; they need
storage, rotation and revocation to be worth anything, which is a lot of
machinery for a portfolio application whose users are a handful of staff at one
business. Password reset flows are already out of scope (Product Scope §6), and
this is the same kind of decision.

### Transport — `Authorization: Bearer`, never a cookie

CSRF protection is disabled, and that is safe *because* of this choice: CSRF
exists because browsers attach cookies to cross-site requests automatically.
They do not do that for an `Authorization` header, so there is no cross-site
request an attacker can cause the browser to authenticate.

## Consequences

- **A token cannot be revoked before it expires.** Deactivating a user takes
  effect immediately anyway, because `AccountStateFilter` checks the database —
  which is the case that actually matters. But a stolen token stays usable until
  it expires, and shortening the lifetime is the only lever without a denylist.
- **One database read per authenticated request**, for the account state. At
  this scale that is the right trade: a cache would need invalidating, and a
  stale cache reintroduces exactly the bug the filter exists to prevent.
- **Phase 2 has to store the token somewhere**, and neither option is perfect.
  `localStorage` is readable by any XSS on the page; an `httpOnly` cookie is not,
  but reintroduces CSRF and contradicts the bearer-header decision above. The
  UI will use `localStorage` and rely on React's default escaping plus a strict
  CSP. Revisit if the app ever renders untrusted HTML.
- Changing a user's role or moving them between businesses would leave a stale
  token valid for up to 12 hours. Neither is possible today — a role is fixed at
  creation and a user belongs to one business for life — so nothing is broken;
  it is a constraint to remember before adding either feature.
