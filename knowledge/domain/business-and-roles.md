---
type: Domain Entity
title: Business & Roles
description: The business is the ownership boundary; users are OWNER or STAFF with a maker-checker permission split.
resource: ../../docs/Invoice_Tech_Scope.md
tags: [domain, rbac, security]
timestamp: 2026-08-28T00:00:00Z
---

# Schema

Registration creates a business + its single OWNER. The owner creates STAFF
accounts directly (temporary password, forced change on first login) and can
deactivate them. Every domain row carries `business_id`; every query filters by
the `biz` claim from the JWT; cross-business access returns 404, never 403.

| Capability | STAFF | OWNER |
|---|---|---|
| Clients, DRAFT invoices, submit | ✅ | ✅ |
| Approve/reject, send, payments | ✗ | ✅ |
| Dashboard & revenue | ✗ | ✅ |
| Team & business/GST settings | ✗ | ✅ |

# Examples

JWT claims: `sub` (user id), `biz` (business id), `role` — and nothing that can
change between logins, which is why `active` and `must_change_password` are read
from the database on every request instead. Owner endpoints carry
`@PreAuthorize("hasRole('OWNER')")`, and a role violation is 403 while a
cross-business row is 404. Deactivated staff fail on their next request, not at
their next login.

New staff receive a generated temporary password, shown once, and cannot reach
any endpoint but `POST /auth/change-password` until they replace it.

# Citations

`docs/Invoice_Product_Scope.md` §3 · `docs/Invoice_Tech_Scope.md` Task 4 ·
`docs/adr/0001-business-as-ownership-boundary.md` (why cross-business is 404) ·
`docs/adr/0002-jwt-shape-and-storage.md` (what the token carries, and why so
little) · `docs/adr/0003-temporary-password-flow.md` (how staff get their first
credential).
Transitions gated by role: [Invoice lifecycle](/domain/invoice-lifecycle.md).
