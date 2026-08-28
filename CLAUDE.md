# Invoicely — working agreement

- **The human writes all application code by hand.** Claude assists with
  documentation, commit messages, README, ADRs, and knowledge/ upkeep only.
  Do not write or edit Java/TypeScript source unless explicitly asked.
- Scope docs are the source of truth: `docs/Invoice_Product_Scope.md` (behaviour,
  copy, status matrix) and `docs/Invoice_Tech_Scope.md` (tasks, gotchas).
- Money is `BigDecimal`, single currency (SGD). Status machine is authoritative
  in the service layer; UI only mirrors it.
- One ADR in `docs/adr/` per non-obvious decision, written when decided.
- `knowledge/` must pass the OKF house validator (pre-commit + CI).
- Commits are per vertical slice, message names the slice.
