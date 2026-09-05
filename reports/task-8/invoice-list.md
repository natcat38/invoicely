# Task 8 — Invoice list screen

Built `web/src/screens/invoices/InvoiceListScreen.tsx` and
`web/src/screens/invoices/useInvoiceList.ts`.

- Status tabs (All/Draft/Awaiting approval/Sent/Overdue/Paid) and an
  optional client `Select`, both reset `page` to 0 on change; ledger table
  (Number, Status, Client, Total, Balance due, Due date) with right-aligned
  `font-mono` money and a mono `Link` on the number; row click also
  navigates (stopPropagation on the Link avoids a double history entry).
  Loading/LoadError(retry)/two distinct EmptyStates (no-filter invites
  creation, filtered offers "Clear filters"); Previous/Next + "Page X of Y"
  only when `totalPages > 1`. Table scrolls in its own container with a
  `min-w-[720px]` floor.
- No deviations from the spec. While reading the foundation I noticed
  `InvoiceDetailScreen.tsx`/`useInvoice.ts` already exist (parent's work) —
  confirmed my query-key/`isPending` conventions match theirs, didn't touch
  those files.
- Did not run build/dev/test per the hard rules; not manually verified
  beyond static reading.
