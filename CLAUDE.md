# Invoicely — working agreement

- **Claude builds this project.** The owner (Natalie) made all product/design
  decisions and is learning Java/Spring separately; implementation, tests,
  docs, and commits are Claude's job.
- **Product/design decisions stay the owner's.** Anything not already settled
  in the scope docs gets asked, not assumed (options + trade-offs, owner picks).
- Source of truth, in order: `docs/Invoice_Product_Scope.md` (behaviour, copy,
  roles, status matrix) → `docs/Invoice_Tech_Scope.md` (stack, schema, tasks
  1–9, gotchas) → `docs/Invoice_Design_Direction.md` (Phase 2 UI layers).
- Build in the Tech Scope's vertical slices, in order. One branch + PR per
  slice; CI green before merge; squash-merge. After Task 1 adds `ci.yml`,
  re-run /protect-repo so `verify` gates main.
- Money is `BigDecimal`, SGD only. Ownership boundary is `business_id` from
  the JWT; cross-business → 404. State errors 409, role errors 403.
- One ADR in `docs/adr/` per non-obvious decision, written when decided.
- `knowledge/` must pass the OKF house validator (pre-commit + CI); update
  concepts in the same PR that changes their behaviour.
- Write code a junior could read — the owner studies this codebase to learn
  Java/Spring, so favor clarity over cleverness and comment the *why*.
