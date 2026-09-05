# Task 8 — Clients screens

## Built

- `web/src/screens/clients/useClients.ts` — `useClientsList` (filters:
  `archived`, `q`, `page`, built into a `URLSearchParams` string that is also
  the query key, per `keys.clients(qs)`), `useCreateClient`, `useUpdateClient`
  (full-replace `PUT`, also used for archive/restore), `useDeleteClient`.
  Invalidation note: `keys.clients(qs)` embeds the whole query string, so
  invalidating with one specific `qs` would not refresh a *different* open
  filter (Active vs Archived, a lingering search). TanStack Query matches
  invalidation keys by prefix, so list-wide invalidation uses the one-element
  prefix `["clients"]` instead — documented inline in `useClients.ts`.
  `useUpdateClient` additionally invalidates `keys.client(id)` via a manual
  `queryClient.invalidateQueries` call in its `onSuccess`, since the static
  `invalidates` list passed to `useApiMutation` can't depend on the mutation's
  own return value.

- `web/src/screens/clients/ClientFormDialog.tsx` — one dialog for create and
  edit. Every editable field, correctly labelled; `uen` and `paymentNotes`
  each carry a hint that they print on the invoice document. Blank optional
  fields are sent as `null` (matching the `Client` type), not `""`.
  `FormError` at the top, `aria-invalid` set per-field from
  `error.fieldErrors`. Submit disabled and relabelled while pending; success
  closes the dialog and toasts.

- `web/src/screens/clients/ClientsScreen.tsx` — `PageHeader` + "New client",
  Active/Archived `Tabs`, debounced (300ms) search, a `Table` (Name, Contact,
  Email, Phone, Actions — no UEN/payment notes column, per the brief) with
  Edit / Archive-Restore / Delete row actions. Delete is confirmed with
  `AlertDialog`; a 409 `client-has-invoices` response surfaces the API's own
  sentence via a `sonner` toast rather than a generic failure. `Loading`,
  `LoadError` (with retry), and two different `EmptyState`s (active vs
  archived) cover the non-happy-path states. Pagination (Previous/Next + "Page
  X of Y") only renders when `totalPages > 1`.

Styled to match the parallel invoices screens closely (same Tabs-left/
filter-right header row, same `data && data.content.length …` guard instead
of destructuring `isPending`/`isError`/`data` — TanStack Query's result type
is a discriminated union, and destructuring loses the tie between `isError`
and `error` being non-null) — read `InvoiceListScreen.tsx` for this pattern
before writing mine.

## Not done / deviations

- `web/src/App.tsx` still routes `/clients` to `PlaceholderScreen` — that file
  is outside my list, so wiring `ClientsScreen` into the router is left for
  whoever owns `App.tsx`.
- No `npm run build` / `vitest` / typecheck was run, per the hard rules —
  everything above is reasoned through by hand (types, existing conventions,
  TanStack Query's partial-match invalidation semantics), not verified by a
  compiler.
