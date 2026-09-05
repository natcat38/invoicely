# Task 7a — invoice document data contract

Built exactly per ADR-0011: `Business.address`/`uen` (nullable, no snapshot); `SettingsRequest`/`SettingsResponse`/`SettingsService`/`SettingsController` expose and edit them (both optional, blank normalised to null, comment on the "changes every already-sent invoice" trade); `InvoiceResponse` gained `amountPaid` (derived as `total - balance`, not summed independently) plus `BusinessSummary` and an extended `ClientSummary`. Tests added to `SettingsApiTest` (round-trip, omission accepted, over-long 400s for both fields) and `InvoiceApiTest` (letterhead + bill-to + non-zero `amountPaid`; separately, unpaid `amountPaid` is `0.00` not null, and unset letterhead/bill-to fields are absent/null not blank).

Deviation: none from the spec. `InvoiceTotals` was not modified (outside file list) — `amountPaid` is recovered in `InvoiceResponse.from` via `totals.total().subtract(totals.balance())`, which is exact since both are already rounded to the same scale. Flagging for the parent, as requested: `InvoiceTotals` could reasonably expose `amountPaid()` directly instead of making every consumer re-derive it, since it already computes `paid` internally before folding it into `balance`.

No build/test run performed per the hard rules.
