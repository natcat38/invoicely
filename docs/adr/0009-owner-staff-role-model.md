# ADR-0009 — Exactly two roles, and role failures are checked separately from state failures

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

Invoicely is a maker-checker tool (Product Scope §2): staff draft invoices,
only the owner approves, sends, and records payments. ADR-0001 already settled
*whose data* a request may touch (the business, via `business_id` from the
JWT — a 404 if it belongs to someone else). A separate question remained
unsettled: *what a given user, once inside the right business, is allowed to
do* — and how that check is expressed and enforced.

Two things had to be decided together:

- **How many roles, and how fine-grained.** The alternative to two fixed roles
  is a permissions system — a set of named capabilities assignable per user,
  independent of a role label. Product Scope §3's capability table
  (`Manage clients`, `Submit for approval`, `Approve & send / reject`,
  `Record payments`, `Dashboard / revenue`, `Manage staff/settings`) has
  exactly one line that splits STAFF from OWNER: everything below "Submit for
  approval" is owner-only, nothing is staff-only. A configurable-permissions
  model would let an administrator invent intermediate roles the product does
  not have a use case for, in exchange for real complexity: a permissions
  table, an admin UI to manage it, and a strictly harder security surface to
  reason about and test.
- **Where the check lives, and how it relates to invoice-status checks.** A
  lifecycle endpoint like `send` can fail two ways that are not the same kind
  of failure: the caller may lack the role to send at all, or the invoice may
  already be in a status that cannot be sent (e.g. already `PAID`). These could
  be merged into one check ("can this user send this invoice right now?") or
  kept as two independent questions.

## Decision

**Exactly two roles, `OWNER` and `STAFF`, fixed at account creation
(ADR-0003) and for the lifetime of the account.** Role is enforced with
per-method `@PreAuthorize("hasRole('OWNER')")` on the controller, kept
strictly apart from status/transition legality, which is enforced separately
in the domain layer.

1. **Two roles, not a permissions table.** `role` is one of two fixed strings,
   set once at account creation and mapped straight to a `ROLE_` authority
   Spring Security checks. There is no notion of a custom or third role, and
   no admin surface to define one — the product has never needed one (Product
   Scope §3 has a single OWNER/STAFF split across every capability), and adding
   one later is a schema and API change, not a data-migration-only change,
   which is an acceptable cost for the complexity it avoids today.
2. **Per-method `@PreAuthorize`, not a class-level annotation.** Every
   owner-only method in `InvoiceController`, `TeamController`, and
   `PaymentController` carries its own `@PreAuthorize`, even where every method
   in the class happens to share the same rule (`TeamController`,
   `PaymentController` — Javadoc calls this out explicitly). A class-level
   annotation is fewer lines today, but a rule that lives on the class rather
   than the method silently stops applying to a method that later gets moved
   out of that class, or silently starts applying to a new method added to it
   without anyone checking whether that new method should really share the
   old rule. Repeating the annotation per method means the rule for a given
   endpoint is visible exactly where that endpoint is, and a reviewer sees it
   without having to check the class declaration too.
3. **Role violations and state violations are different HTTP status codes,
   checked in different places, on purpose.** `@PreAuthorize` only knows about
   roles and produces **403**; `InvoiceStatus.canTransitionTo` only knows about
   legal state transitions and produces **409** (via `GlobalExceptionHandler`).
   Neither check is aware of the other. This means "staff attempting to send"
   and "an already-`PAID` invoice being sent again" are answered by two
   independent mechanisms that cannot be confused with each other in code, and
   are tested independently: `InvoiceStatusTest` checks the transition matrix
   with no Spring context at all (pure unit test, milliseconds); the
   `@PreAuthorize` role checks are tested over real HTTP with real JWTs
   (`InvoiceLifecycleApiTest`).
4. **A role check never substitutes for the ownership check.** Role
   (403-or-not) and business ownership (404-or-not, ADR-0001) are also two
   independent axes: a `STAFF` user's role authorizes or forbids an action
   in the abstract, without reference to which business's data they are
   trying to act on. The `business_id` scoping happens at the repository layer
   regardless of role.

## Consequences

- Adding a third role (e.g. a bookkeeper who can see revenue but not manage
  staff) is a real schema and enum change, not a config change — accepted
  because Product Scope §3 has never asked for one, and speculative
  generality here would be complexity paid for a feature nobody has requested.
- Every owner-only method repeats the same `@PreAuthorize` string. That
  repetition is deliberate, the same trade the project already made for
  `findByIdAndBusinessId` in ADR-0001: it looks redundant, and the redundancy
  is exactly what makes a missing check visible in review, because it looks
  different from its neighbours.
- Because role and state are checked independently, a request can legitimately
  fail on state (409) even for a user whose role would have permitted the
  action, and vice versa — callers (and the UI) have to handle both
  independently rather than assuming a single "not allowed" bucket.
- A user's role is immutable after creation in Phase 1. Changing a user's role
  after the fact is out of scope; ADR-0002 already notes the related
  consequence that a role change would leave a stale JWT valid for up to 12
  hours, which would need addressing together with adding role changes at
  all.
