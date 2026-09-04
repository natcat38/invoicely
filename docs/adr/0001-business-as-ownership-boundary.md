# ADR-0001 — The business is the ownership boundary, and missing means 404

**Status:** accepted · **Date:** 2026-09-05 · **Decided by:** Natalie

## Context

Invoicely is multi-tenant: many businesses share one database, and every
client, invoice, line item and payment belongs to exactly one of them. Something
has to decide which rows a request may touch.

Two candidates:

- **The user.** Natural in a single-person tool — you see what you created. But
  Invoicely is explicitly a team tool (Product Scope §2): staff draft invoices
  that the owner approves and sends, and both must see the same rows. Scoping by
  user would mean the owner could not open the invoice awaiting their approval.
- **The business.** Everyone in the business sees the same data; what differs
  between OWNER and STAFF is what they may *do* with it, not what they may see.

There is also a question of what to return when a request names a row belonging
to someone else. `403 Forbidden` is the honest status code, but it confirms the
row exists — an attacker walking `/invoices/1`, `/invoices/2`, … learns the size
and shape of other businesses from the 403/404 split alone.

## Decision

**The business is the ownership boundary**, and the business id comes only from
the `biz` claim on the JWT.

1. Every business-owned table carries a `business_id` column (V1 migration).
2. Every single-row lookup is `findByIdAndBusinessId(id, businessId)`. There is
   no plain `findById` in application code.
3. `business_id` is never read from a request body or query parameter, only from
   the token. A body that contains one is ignored, not honoured.
4. A row that exists but belongs to another business returns **404**, exactly as
   if it were absent. 403 is reserved for the *other* axis of failure: a user
   inside the right business whose role does not permit the action.
5. `created_by`, `sent_by` and `recorded_by` are audit attribution. They record
   who did something. They never decide who may.

## Consequences

- Repository methods look repetitive — every finder carries the business id.
  That repetition is the point: an unscoped finder is a data leak, and it is
  easy to spot in review precisely because it looks different from its
  neighbours.
- Two failure axes have to be tested separately, because they mean different
  things: wrong business → 404 (`DomainPersistenceTest`), wrong role → 403
  (Task 4 onwards).
- Staff cannot have private drafts. This is intended — the owner has to be able
  to see a draft in order to approve it.
- One user belongs to one business permanently. Multiple businesses per user is
  out of scope (Product Scope §6); supporting it later would mean a join table
  and a business-picker at login, not a change to this rule.
- The 404-for-403 substitution means logs are the only place a genuine
  cross-business attempt is visible. Worth remembering when debugging a report
  of a "missing" invoice.
