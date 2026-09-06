# Task 9 — Team screen

Built `web/src/screens/team/{useTeam.ts,TeamScreen.tsx,AddStaffDialog.tsx}`:
`useTeamList`/`useAddStaff`/`useSetActive` over a local `["team"]` key (no
`keys.team` added — `hooks.ts` is out of scope); the Team table (Name, Email,
Role, Invoices created, Last active, Status, Actions) with a local
`TeamStatusBadge` (quiet outline for Active, filled warning tone for
Deactivated); Deactivate hidden on the caller's own row (`session.user.userId`)
with the API's 409 as the real enforcement, confirmed via `AlertDialog`;
Reactivate unconfirmed. `AddStaffDialog` switches in place from the create
form to a one-time temp-password screen (`font-mono`, selectable, copy button
with a `navigator.clipboard` failure fallback) — no auto-close, no way back to
the form.

Nothing built outside the file list; no deviations from the brief.
