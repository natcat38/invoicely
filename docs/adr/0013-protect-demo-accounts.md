# ADR-0013 — The shared demo logins cannot be changed or switched off

**Status:** accepted · **Date:** 2026-10-23 · **Decided by:** the owner, recorded by Claude

## Context

The live demo publishes its owner and staff password in the README. Anyone with
it can call the API directly (Swagger included), and two calls were enough to
lock every later visitor out:

- `POST /auth/change-password` needs only the current password, which is public.
- The owner can deactivate the staff login (`PATCH /team/{id}`); only
  deactivating *yourself* was blocked.

`DemoDataSeeder` cannot repair either. It returns early once the owner's email
exists, so a restart restores nothing.

## Decision

When the `demo` profile is active, the API refuses both actions for the two
seeded accounts with `403` and the problem type `demo-account-protected`. The
UI already prints a failed request's `detail`, so the message reaches the
visitor unchanged.

`DemoAccountGuard` holds the rule. It is a bean everywhere, but it only knows
any demo emails when `DemoDataProperties` exists, and that bean is only
registered under the `demo` profile. Outside that profile it is a no-op, so no
other deployment changes behaviour.

Only the credential and availability paths are guarded. These are the two code
paths that set a password hash or flip `active`: `AuthService.changePassword`
and `TeamService.setActive`. No endpoint edits a user's email, role or name.
Reactivation stays allowed because it can only restore access.

## Consequences

- A new endpoint that touches a user's password hash, `active` flag or email
  must call the guard. The guard's Javadoc says so.
- The guard cannot repair an account that was already changed before it
  shipped. That needs a one-off database fix.
- Settings and drafts on the demo business remain editable by whoever is
  signed in. The demo has no nightly reset, and this ADR does not add one.
- The login throttle is still one bucket per proxy address on Render, so ten
  failed logins by anyone can still block everyone for 15 minutes. That is a
  separate finding.
