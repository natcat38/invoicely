---
type: Domain Entity
title: Business & Roles
description: The business is the ownership boundary; users are OWNER or STAFF with a maker-checker permission split.
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

JWT claims: `sub` (user id), `biz` (business id), `role`. Owner endpoints carry
`@PreAuthorize("hasRole('OWNER')")`. Deactivated staff fail auth on the next
request, not just at next login.

# Citations

`docs/Invoice_Product_Scope.md` §3 · `docs/Invoice_Tech_Scope.md` Task 4.
Transitions gated by role: [Invoice lifecycle](/domain/invoice-lifecycle.md).
