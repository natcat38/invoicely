# ADR-0003 — Owners create staff with a one-time temporary password, shown once and forced to change

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

The owner adds staff accounts directly; there are no invite emails (Product
Scope §2), because the application sends no email at all — "send" an invoice
means a status change and a document, not an SMTP conversation. So the owner
needs some way to hand a new staff member their first credential.

## Decision

**`POST /team` generates a temporary password, returns it exactly once, and
marks the account as needing a change.**

1. The server generates the password — never the owner. A human choosing a
   colleague's first password reliably picks a weak one, and often reuses it
   across staff.
2. It is generated with `SecureRandom` from a deliberately trimmed alphabet:
   no `O`/`0`, no `l`/`1`/`I`. The owner is going to read this aloud or copy it
   off a screen, and an ambiguous character turns into a support conversation.
3. It is hashed with BCrypt like any other password. **The plain value exists
   only in the HTTP response to the request that created the account.** It is
   never stored, never logged, and no endpoint can retrieve it afterwards.
4. The new account gets `must_change_password = true`. `AccountStateFilter`
   then answers every request from that user with **403** and the problem type
   `/problems/password-change-required`, except `POST /auth/change-password`.
   The UI redirects on that type and shows the interstitial with the §5.1 copy:
   *"Set a new password to continue."*
5. Changing the password clears the flag and returns a fresh token.

## Consequences

- **If the owner loses the temporary password, it cannot be recovered.** The
  remedy is to deactivate the account and create another, which is acceptable
  because password reset flows are explicitly out of scope (Product Scope §6).
  A "regenerate temporary password" action would be the natural addition if this
  proves annoying in practice.
- **The password travels in a response body**, so it is only as private as the
  transport. Over HTTPS that is fine; it is one more reason the deployed
  environment must not be plain HTTP.
- The forced change is enforced by a filter rather than by each endpoint, so a
  new endpoint added later is covered without anyone remembering to cover it.
  The cost is that the rule lives away from the endpoints it affects — the
  filter's Javadoc names the one path it lets through.
- 403 rather than 401 is deliberate: the caller **is** authenticated, and their
  token is perfectly valid. What they lack is permission to do anything else
  yet. A 401 would tell the UI to send them back to the login screen, which is
  the opposite of what should happen.
- Because the temporary password satisfies the same 8-character minimum as any
  other, nothing special is needed on the change-password endpoint to accept it.
