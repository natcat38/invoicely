# Task 9 — Settings screen

## Built
- `web/src/screens/settings/useSettings.ts` — `useSettings()` (`GET /settings`) and
  `useUpdateSettings()` (`PUT /settings`, full replace), with a local `SETTINGS_KEY`
  query key (not added to `lib/hooks.ts`'s `keys` object — that file is out of scope
  and Settings is its only reader anyway).
- `web/src/screens/settings/SettingsScreen.tsx` — outer `SettingsScreen` does the
  fetch/Loading/LoadError dance, then hands the loaded `SettingsResponse` to an inner
  `SettingsForm` whose state is initialised with `useState(() => ...)`, following
  `ClientFormDialog`'s pattern (no `setState`-in-`useEffect`).
  - **Business details** card: name, address (`Textarea`), UEN — with a UI note that
    these print on the letterhead, and a code comment (citing ADR-0011) explaining
    that editing them changes what every already-sent invoice prints, since the
    letterhead renders live rather than being snapshotted.
  - **GST and payment terms** card: native `<input type="checkbox">` for
    GST-registered (styled with `accent-app-accent`/existing tokens, not hidden or
    disabled when off); a percent-based GST rate input that converts to/from the
    API's fraction via `fractionToPercentText`/`percentTextToFraction`, rounding to 4
    decimal places (matching `SettingsRequest.gstRate`'s `@Digits(4)`) to dodge
    binary-float noise like `0.09 * 100 === 9.000000000000002`; a reassurance
    paragraph that the rate is snapshotted at send time; and a `Select` restricted to
    7/14/30 days with a due-date hint.
  - `FormError` at the top, `aria-invalid` from both server field errors and the
    client-side GST-percent range check, disabled submit + pending label, `sonner`
    toast on success.

## Verified against
`SettingsRequest.java`/`SettingsController.java` for field names and validation
(`name`, `gstRegistered`, `gstRate` ≤ 0.9999/4dp, `defaultPaymentTermsDays`,
`address`, `uen`), Product Scope §5.2a, and ADR-0011.

## Deviations / could not do
None. Stayed inside the two-file list; did not touch `App.tsx`, `lib/hooks.ts`, or
run any build/lint/test command.
