# Invoicely UX audit, October 2026 (web/)

The UX audit of the React UI, its owner decisions and its fix log, moved here
from a local working folder on 2026-11-09 so the record lives with the code.
Screenshot names in the findings table refer to the original audit folder
(`UXP/shots/...`), which is not kept; a few key before/after shots are in
`shots/` beside this file.

Scope: React UI in `web/` of natcat38/invoicely at origin/main `d145b98`. Read-only: nothing edited, no issues, no PRs.
Method: ran the API (demo profile) and the Vite UI locally from a detached worktree with the seeded demo logins (`owner@invoicely.demo`, `staff@invoicely.demo`). The public demo was not touched. Walked login, dashboard, invoice list, builder (line items, GST preview), detail, approve/reject/payment, empty and error states, at 1440x900 and 390x844 (plus 320 for reflow). Screenshots are in `UXP/shots/inv/` (`UXP` = `C:/Users/natal/Documents/Coding/ux-polish-2026-10-23`). Pinned-bar checks use instant scrolling (the app has no `scroll-behavior: smooth`; re-verified after the shoot.mjs fix). No open GitHub issues exist, so nothing here duplicates a tracked item.

## Verdict
Desktop is calm and mostly sound: the list, dashboard and detail pages fit in about one screen, and the Design Direction is followed closely. The phone experience is where it feels clunky: a 293px sidebar block (35% of the screen) sits above every page and scrolls away, the builder scrolls sideways at page level, and the invoice document, the product's centrepiece, hides its Qty, Unit price and Amount columns behind an unmarked inner scroll. The biggest single win is a phone layout pass (fix the builder overflow, stack the document's line items, compact the app shell), which removes all three P0s.

Positives worth keeping: filters live in the URL so Back works, empty states have actions, the login wake notice explains the 200 s cold start, GST preview matches the server to the cent (110.04 -> 9.90 checked), inputs are 16px on phone, and the focus rings are visible.

## Measurements
| Page (owner) | Desktop screens | Phone screens | Page-level horizontal scroll at 390 / 320 |
|---|---|---|---|
| /dashboard | 1.0 | 1.6 | none |
| /invoices | 1.0 | 1.0 | **yes, 400 px / 400 px** (tabs row) |
| /invoices/new | 1.0 (10 lines: 1.4) | 1.2 | **yes, 570 px / 570 px** (form card) |
| /invoices/:id | 1.1-1.3 | 2.0-2.3 | none (document scrolls inside itself, 480 px content in 276 px box) |
| /clients, /team | 1.0 | 1.0 | none (inner table scrollers 938 and 760 px wide) |

Shell on phone: nav block height 293 px (owner) / 253 px (staff), `position: static`, nothing sticky or fixed anywhere in the app. Desktop sidebar is also not fixed: on a 10-line draft, Sign out sits at y=1207 at scroll 0 (below the 900 px fold) and the "Create invoice" button is at y=-327 after scrolling.

## Findings
IDs `INV-UX-n`. Screenshot names are under `UXP/shots/inv/`. File paths are relative to `web/src/`.

| ID | Sev | Rule | What the user experiences | Evidence | Fix | Skill | Effort |
|---|---|---|---|---|---|---|---|
| INV-UX-1 | P0 | S-LAY-4 | New-invoice page on a phone scrolls sideways: the form card is 570 px wide in a 390 px viewport (also at 320). Inputs and the Card's right edge are cut off. | `owner-invoices_new-phone-full.png`, `staff-builder-noclient-phone.png`; `screens/invoices/InvoiceBuilderScreen.tsx:236` (grid with one implicit auto column; the preview's 30rem table sets the column's min-content width) | Change to `grid-cols-[minmax(0,1fr)] lg:grid-cols-2` and add `min-w-0` to both grid children. Re-measure `scrollWidth` at 320. | fixing-accessibility, impeccable harden | S |
| INV-UX-2 | P0 | S-LAY-4, S-LAY-1 | On a phone the invoice document shows description and Total only. Qty, Unit price and Amount are off to the right inside an unmarked inner scroller (480 px of table in a 276 px box), so line amounts are invisible on the detail page, staff's after-submit view, and the builder preview. | `owner-invoices_2-phone-full.png`, `staff-submitted-phone-2.png`; `components/document/InvoiceDocument.tsx:129-130` (`min-w-[30rem]` plus `p-8` leaves 276 px) | Below `sm`, render each line as a stacked block (description on top, `qty x unit price` and amount on one right-aligned row beneath), keep the real table from `sm` up. Also drop padding to `p-5` below `sm`. Keep the amounts in mono tabular figures. | impeccable (layout, adapt) | M |
| INV-UX-3 | P0 | S-NAV-1, S-LAY-1 | On a phone the whole dark sidebar stacks above every page: brand, a nav that wraps to two rows ("Settings" alone on line 2), user block and Sign out. It takes 293 px of 844 px before any content, then scrolls away, so on a long page (detail, builder) the user must scroll to the top to navigate or sign out (nav top at y=-790 on a detail page). The Design Direction says "fixed dark sidebar"; on phones it is not fixed. | `owner-dashboard-phone-full.png`, `owner-invoices-phone-full.png`; `shell/AppShell.tsx:34-35` (`md:grid`, no sticky), `:43` (`flex-wrap`), `:62` (user block inside the stacked aside) | Replace the stacked aside below `md` with a compact sticky top bar (about 56 px): wordmark, current section, menu button; or a bottom tab bar. Move user name, role and Sign out into the menu. **Blocked by D1.** | impeccable (adapt, layout), frontend-design | M |
| INV-UX-4 | P1 | S-LAY-4, S-LAY-1 | Tables on a phone show Number, Status, Client and clip the rest: the invoice list loses Total, Balance due and Due date; the dashboard's approval queue loses Total and Status, the two things an owner checks before approving. No visual hint that the table scrolls. Same on Clients (640 px) and Team (760 px). | `owner-invoices-phone-full.png`, `owner-dashboard-phone-full.png`; `screens/invoices/InvoiceListScreen.tsx:128` (`min-w-[720px]`), `screens/dashboard/DashboardScreen.tsx:162` (`min-w-[560px]`), `screens/clients/ClientsScreen.tsx:184`, `screens/team/TeamScreen.tsx:101` | Below `md`, render rows as compact cards (number + status badge, client, total and due date on one line); keep the table from `md` up. **Blocked by D5.** | impeccable (adapt) | M |
| INV-UX-5 | P1 | S-LAY-4 | The six status tabs on /invoices are wider than a 390 px (and 320 px) screen. They push the whole page 10 px sideways, so the page itself scrolls horizontally on the main list. | `owner-invoices-phone.png`; `screens/invoices/InvoiceListScreen.tsx:59-67`, `components/ui/tabs.tsx` (`TabsList` is `inline-flex w-fit`) | Wrap the tabs in `<div className="max-w-full overflow-x-auto">` and give `TabsList` `min-w-max`; `scroll-snap` optional. Or use a status Select on phone. | impeccable (adapt) | S |
| INV-UX-6 | P1 | S-NAV-1 | On desktop the sidebar is a normal column, not fixed. Anything taller than one screen (an invoice with 6+ lines, the builder) scrolls the nav away, and Sign out ends up at the bottom of the document, at y=1207 on a 10-line draft. | `owner-builder-10lines-bottom-desktop.png`; `shell/AppShell.tsx:35` (`md:min-h-dvh`; the aside stretches to the grid row height) | `md:sticky md:top-0 md:h-dvh md:self-start md:overflow-y-auto` on the aside (matches the Design Direction's "fixed" sidebar). | impeccable (layout) | S |
| INV-UX-7 | P1 | S-NAV-1, S-LAY-1 | Cancel and "Create invoice" live in the page header and scroll out of sight as soon as the user adds a few lines (top at y=-327 with 10 lines), so after filling a long invoice the user scrolls back up to save. On phone the preview is below the form, so the save button is even further away. | `owner-builder-10lines-bottom-desktop.png`; `screens/invoices/InvoiceBuilderScreen.tsx:218-231` | Make the header a sticky bar (`sticky top-0 z-10 bg-app-bg/95 backdrop-blur py-3`, with `scroll-margin-top` on form fields so focus never hides under it, S-NAV-2). On phone, a sticky bottom action bar with `padding-bottom: env(safe-area-inset-bottom)`. | impeccable (layout), fixing-accessibility | S |
| INV-UX-8 | P1 | S-LAY-5 | In the builder's side-by-side layout the preview paper is clipped on desktop: the AMOUNT header and amounts are cut off ("0.0|") because the table needs 480 px and the container has 474 px. The "showcase" shows its money column truncated on first view. | `owner-invoices_new-desktop.png` (measured `scrollWidth 480 / clientWidth 474`); `components/document/InvoiceDocument.tsx:130` (`min-w-[30rem]`) and `:54` (`sm:p-10`), `InvoiceBuilderScreen.tsx:236` (equal columns) | Reduce document padding inside the builder (`sm:p-8`), lower the table's `min-w` to `26rem` from `sm`, and use `lg:grid-cols-[minmax(0,5fr)_minmax(0,6fr)]` so the paper gets the wider column. | impeccable (layout) | S |
| INV-UX-9 | P1 | S-FB-4 | Builder validation is weak. (a) There are no client-side checks (`noValidate`, nothing before submit), so every mistake costs a round trip. (b) Errors appear only in a banner above the form, which pushes the form down about 144 px (layout jump). (c) The banner says "Describe what is being billed." without saying which line, and nothing is highlighted. (d) A missing client shows only "Client was not found." after the other errors are fixed. (e) Typing "abc" as quantity gives the raw "Failed to read request". | `owner-builder-empty-submit-desktop.png`, `staff-builder-noclient-phone.png`; `InvoiceBuilderScreen.tsx:170-196` (submit builds the body without validating), `:217` (`noValidate`), `:222` (banner), `screens/FormError.tsx` | Validate before submit: client chosen, description non-empty, quantity a positive number, unit price a number at least 0. Show a message under each field with `aria-invalid` and `aria-describedby`, focus the first invalid field, keep the summary at the top only as a pointer. Map API `fieldErrors` (e.g. `lineItems[0].description`) to the matching line. Replace the two raw API strings with plain sentences (Choose a client; Enter a quantity such as 2 or 1.5). Reserve space so the banner does not shift the layout. | impeccable (harden, clarify), fixing-accessibility | M |
| INV-UX-10 | P1 | S-FB-3 | "Send" and "Approve & send" fire immediately with only a toast afterwards. Sending locks the GST rate and number, and the invoice can no longer be edited, so a mis-tap on a phone (buttons sit side by side with Reject) cannot be undone. | `screens/invoices/InvoiceActions.tsx:55` (`run`), `:88` and `:103` (`run("send", ...)`), no dialog | Add a confirm step for send. **Blocked by D2.** | impeccable (harden), clarify | S |
| INV-UX-11 | P1 | S-FB-4 | Staff see a wrong due date in the preview. The default due date comes from `/settings` (owner-only), so for staff the Due date field is empty ("dd/mm/yyyy") and the preview falls back to the issue date (23 Oct), while the saved invoice shows 22 Nov. The preview contradicts the result, in the one place meant to show "what the client gets". | `staff-builder-noclient-phone.png`; `InvoiceBuilderScreen.tsx:134-135` (derived only if `settings.data`) and `:338` (preview falls back to `issueDate`) | Do not fall back to the issue date. Either show "Set by payment terms on save" in the preview and in the field's placeholder for staff, or have `/auth/me` carry the payment-terms days so the same default is computed for everyone (an API change, so flag it for the owner of the API). | impeccable (clarify) | S |
| INV-UX-12 | P1 | S-A11Y-1 | Touch targets are small on phone. Buttons and inputs are 32 px high, `size="sm"` buttons and tabs 28 px, invoice-number links 109x18 px, breadcrumb link 55x17 px, "See all" 20 px, the Settings checkbox 16 px. Measured 15-22 interactive elements under 44 px per page. Rows are clickable by mouse only through an `onClick` on the `<tr>`, so the real hit area is the 18 px link. | `components/ui/button.tsx` (`h-8`, `sm: h-7`), `components/ui/input.tsx` (`h-8`), `components/ui/select.tsx:46` (`h-8`), `screens/invoices/InvoiceListScreen.tsx:134-153`, `screens/invoices/InvoiceDetailScreen.tsx` breadcrumb | Add a responsive size: inputs, selects and buttons `h-11 md:h-8` (or a global `@media (pointer: coarse)` rule). Make the row link fill the cell (`after:absolute after:inset-0` with `relative` on the row). Give the breadcrumb `py-3`. Target 44 px on phone; 24 px minimum on desktop (the links fail even that). | fixing-accessibility | M |
| INV-UX-13 | P2 | S-A11Y-3 | After closing the Reject dialog (Escape or Cancel), focus drops to the page body instead of returning to the Reject button. A keyboard user restarts from the top of the sidebar. Payment dialog uses the same pattern. | measured: dialog opens with focus in the note field (good), after Escape `document.activeElement` is `BODY`; `InvoiceActions.tsx:98,114` (dialog opened from a plain button's state, no `DialogTrigger`), `PaymentDialog.tsx:48` (same pattern, not separately measured) | Keep a ref to the opening button and call `triggerRef.current?.focus()` in `onCloseAutoFocus`, or wrap the button in `DialogTrigger`. | fixing-accessibility | S |
| INV-UX-14 | P2 | S-A11Y-3 | There is no skip link. Every page puts 6 tab stops (5 nav items and Sign out) before the content. | tab order measured on `/invoices/2`: Dashboard, Invoices, Clients, Team, Settings, Sign out, then content; `shell/AppShell.tsx` | Add a visually hidden "Skip to content" link as the first focusable element, targeting `<main id="main" tabIndex={-1}>`. | fixing-accessibility | S |
| INV-UX-15 | P2 | S-FB-1, S-FB-2 | Every screen shows a bare grey "Loading..." line with no reserved height (content jumps when data arrives). A failing GET (5xx or dropped connection) keeps saying "Loading..." for about 3 s (1 s + 2 s retry back-off) before any error appears; 4xx fail fast. | `owner-invoices-loading-desktop.png`, `owner-invoices-500-desktop.png` (caught at 1.5 s still loading), `components/Page.tsx:57-63`, `main.tsx:20-26` | Give `Loading` a `min-h` that matches the content, add "Still loading..." after about 1 s. Allow one retry for GETs. The skeleton question is **D3**. | impeccable (harden), emilkowalski break-ui | S |
| INV-UX-16 | P2 | S-A11Y-2 | Small grey labels on the document ("Invoice", "Bill to", "Issue date", "Due date", table headers) use `text-ink/50` at 12-14 px. Computed estimate against white is about 3.1:1; the "-" placeholders at `/40` are about 2.4:1. App-layer muted text (`#64748b` on `#f8f9fb`) is about 4.5:1, on the line. | `components/document/InvoiceDocument.tsx:86,94,116,120,132` | Implementer measures and raises these to at least 4.5:1 (S-A11Y-2 target) by darkening the ink alpha or using a solid token; check the app muted token has headroom on hover or disabled states. | fixing-accessibility | S |
| INV-UX-17 | P2 | S-LAY-1, S-NAV-4 | On a phone the detail page puts three full-size stat cards (about 130 px each, about 400 px total) between the header and the document, repeating figures the document shows again. The detail page runs 2.0-2.3 screens. | `owner-invoices_2-phone-full.png`; `screens/invoices/InvoiceDetailScreen.tsx:74,122-125` (`sm:grid-cols-3`, so single column below 640 px) | Below `sm`, use one compact three-up strip (`grid-cols-3`, smaller type, `p-3`) or show only Balance due. | impeccable (layout) | S |
| INV-UX-18 | P2 | S-FB-2 | A staff member who submits an invoice sees the badge change to "Awaiting approval" and all buttons vanish, with no sentence on what happens next or why Edit is gone. | `staff-submitted-phone-2.png`; `InvoiceActions.tsx:96` (pending block is owner-only, renders nothing for staff) | In the detail header, for staff on `PENDING_APPROVAL`, show a status line: "Waiting for the owner to approve. You cannot edit it while it is pending." (functional copy, review wording in the PR). | impeccable (clarify) | S |
| INV-UX-19 | P2 | S-NAV-3, S-ID-2 | Browser tab says "Invoicely" on every route (measured on all 13 routes), so tabs and history entries are indistinguishable. There is no meta description and no OG image. | `index.html:10` (`<title>Invoicely</title>`, no per-route title), `index.html` (no description or og tags); `public/` holds only `favicon.svg` and `_redirects` | Set `document.title` per route from `PageHeader` (for example "Invoices - Invoicely", "INV-2026-0002 - Invoicely"). Add a meta description. The OG image is **D4**. | impeccable (clarify) | S |
| INV-UX-20 | P2 | S-FB-2 | An expired or invalid session sends the user straight to the login page with no explanation (tested by faking a 401: lands on `/login`, no message). | `lib/hooks.ts:36` (`signOut()` on re-auth), `screens/LoginScreen.tsx` (no notice) | Pass `state: { reason: "expired" }` on redirect and show a one-line notice on the login page ("Your session ended. Sign in again."). | impeccable (clarify) | S |

## Decisions for Natalie

**D1. Phone navigation pattern** (blocks INV-UX-3). Protects the first screen of every phone page and keeps nav and Sign out reachable. Today the app shell costs 35% of the viewport. Options:
1. Compact sticky top bar with a menu button that opens a sheet (brand, nav, user, Sign out). Small footprint (about 56 px), familiar, but nav is one tap deeper.
2. Bottom tab bar with 2 tabs for staff and up to 5 for owners, user and Sign out inside Settings or a profile sheet. Thumb-reachable and always visible, but takes about 56 px at the bottom and needs safe-area padding; owner's 5 tabs are the practical maximum.
3. Keep the stacked block but make it compact and sticky (single horizontal-scroll row of nav links, brand shortened, user block moved behind a menu). Least change to the current look, but a sticky row still costs about 90 px.

**D2. Confirming "Send" and "Approve & send"** (blocks INV-UX-10). Protects against sending the wrong invoice, since sending locks GST and the document. Lowering protection means faster approvals. Options:
1. Confirm dialog summarising "Send INV-... to Client for S$X? GST and number lock." Safest; adds one tap per approval.
2. Undo window: the button sends, a toast offers Undo for about 8 s. Faster, but the API sends immediately, so it needs a delayed call (client-side hold) or an API unsend, which does not exist and would conflict with the lifecycle rules.
3. No confirmation; relabel and separate the buttons visually (Send as the only primary, Reject as a text link), toast stays. Fastest; the risk of a mis-tap remains.

**D3. Loading style** (blocks only the skeleton part of INV-UX-15; the min-height and slow-state message can go ahead). The Design Direction explicitly rules out skeletons. Options:
1. Keep plain "Loading..." (honour the Design Direction), add reserved height and a slow-state line.
2. Skeleton rows on tables only (list, dashboard, clients, team), plain text elsewhere. Reduces layout jump on the busiest screens, departs from the written rule.
3. Small inline spinner next to the page title while content area stays reserved and empty. Light, avoids shimmer, but a blank area can read as "nothing here".

**D4. OG image and logo mark** (blocks only the OG part of INV-UX-19). Today the identity is a serif text wordmark plus favicon. An OG image decides how the demo link looks when shared (the README links to the live demo). Options:
1. Generated card: wordmark on the app-sidebar navy with the one-line pitch. Cheap, consistent, no new art.
2. Cropped screenshot of the dashboard or builder as the card. Shows the product; goes stale as the UI changes.
3. No OG image (internal-style tool, link usually sent with an explanation). Zero effort; shared links look bare.

**D5. Phone list pattern** (blocks INV-UX-4). Changes how the owner reviews money on a phone. Options:
1. Card rows below `md` (number and status, client, total and due date). Everything visible, easy to tap; loses column scanning and sorting-by-eye.
2. Keep the table but pin the first column and add a visible scroll hint (fade edge) and the key columns first (Number, Status, Total). Closest to today; still requires side-scrolling for due date and balance.
3. Reduce columns on phone (hide Balance and Due date, show them on the detail page). Simplest; hides due date, which drives overdue chasing.

## Branding status
| Item | Status |
|---|---|
| Name and logo in header | Partial: serif "Invoicely" text wordmark in the sidebar and on the auth screens; no logo mark. |
| Favicon | Present (`public/favicon.svg`). |
| `<title>` | Partial: fixed "Invoicely" on every route (INV-UX-19). |
| OG image and meta description | Missing (D4, INV-UX-19). |
| Consistent palette and type | Present: tokens in `index.css`; Inter for the app, Fraunces for the invoice number, JetBrains Mono for amounts. One minor inconsistency: the invoice number is mono in the app header and serif inside the document, which the Design Direction intends. |

## Fix batches (non-blocked findings)
1. **Phone layout breakage** (INV-UX-1, 2, 5, 8, 17): remove all page-level horizontal scroll at 320 and 390, make the document readable on a phone, and stop the builder preview clipping.
2. **Shell and builder ergonomics** (INV-UX-6, 7, 9, 11, 19, 20): fixed desktop sidebar, sticky builder actions, real field validation, a correct staff preview, per-route titles and a session-expired notice.
3. **Accessibility and states polish** (INV-UX-12, 13, 14, 15, 16, 18): touch-target sizes, focus return, skip link, loading and error timing, contrast, and the staff pending-approval line.

Blocked until Natalie decides: INV-UX-3 (D1), INV-UX-4 (D5), INV-UX-10 (D2), the skeleton part of INV-UX-15 (D3), the OG part of INV-UX-19 (D4).

## Fix log

### Batch "Phone layout breakage" (INV-UX-1, 2, 5, 8, 17)
- PR https://github.com/natcat38/invoicely/pull/22 merged 2026-10-23T12:12:13Z (squash, all checks green).
- Fixed: INV-UX-1, 2, 5, 8, 17. Skipped by instruction: INV-UX-3, 4, 10, 15, 19.
- Page-level horizontal scroll at 390 and 320: gone on /invoices (400 px) and /invoices/new (570 px). Document lines now readable on a phone (stacked when the paper is under 32rem wide). Builder preview at 1440: 480 px table in 474 px box, now 525 in 525.
- Phone detail page: 2.06 to 1.86 screens (320 px: 2.63 to 2.36). Stat block about 400 px to about 175 px.
- Deviations from the report: container query (not `sm`) switches the document; stats are rows, not a three-up strip; 5fr/6fr split from `xl`, not `lg` (the form's line row is already squeezed at 1024, a separate older issue). Shots: shots/fix/invoicely-{before,after}-*.

### PR 1 "Shell and nav" (INV-UX-3, 6, 7, 9, 11, 19 incl. OG, 20)
- PR https://github.com/natcat38/invoicely/pull/23 merged 2026-10-23T17:47:58Z (squash, all checks green).
- Phone chrome 293 px (scrolls away) to 56 px sticky top bar with a menu sheet; desktop sidebar sticky (Sign out y=1207 to 856); builder Create button always visible (y=-327 / -2422 to 22 / 788); inline field validation with no layout shift; staff preview no longer shows a wrong due date; per-route titles; OG card (`web/public/og.png`, source `web/og-card/`); "Your session ended" notice. No page-level horizontal scroll at 320 px on 7 pages.
- Deviations: session-expired uses an auth-context flag, not router state. `og:image` URL assumes the README demo host serves the UI.

### PR 2 "Phone cards and accessibility" (INV-UX-4, 12, 13, 14, 16, 18, 15 min-height and slow-state only)
- PR https://github.com/natcat38/invoicely/pull/24 merged 2026-10-23T17:58:16Z (squash, all checks green).
- Invoice list and dashboard lists are cards below `sm` (list 1.0 to 1.4 screens, dashboard 1.3 to 1.8). Phone targets under 44 px: dashboard 9 to 6, list 15 to 9, detail 3 to 0, clients 16 to 2; none under 24 px. Focus returns to the Reject / Record payment buttons. Skip link. Document label contrast 3.10 to 4.82. Failing GET shows its error after 2.0 s (was 3.8 s).
- Skipped: INV-UX-4 cards for Clients and Team (the decided card layout is invoice-specific; they still scroll inside their box). Open question: cards below `lg` instead of `sm` (table scrolls inside its box from 640 to about 1000 px).

### PR 3 "Send confirm and skeletons" (INV-UX-10 = R7 option 1; skeleton part of INV-UX-15 = R8 option 2)
- PR https://github.com/natcat38/invoicely/pull/25 merged 2026-10-23T18:04:48Z (squash, all checks green).
- Confirm dialog before Send and Approve & send (keyboard-checked: focus trap, Escape and Cancel return focus, no request before confirm). Skeleton rows on list, dashboard list, clients, team; none with reduced motion. Design Direction updated for both.
- All three PRs ran against a local API with the seeded demo logins; the public demo was not touched.

### PR 4 "Clients and Team cards, cards below lg" (INV-UX-4 remainder; open question from PR 2)
- Owner decisions 2026-11-09: Clients and Team also become cards on narrow
  screens, and every list (invoices, dashboard queue, clients, team) switches
  from cards to the table at `lg` (1024 px) instead of `sm` (640 px). Recorded
  in `docs/Invoice_Design_Direction.md` (Quality floor).
- Hidden table width, measured as table width minus its scroll box (px):

  | Page | 768 before | 768 after | 1000 before | 1000 after | 1024 after |
  |---|---|---|---|---|---|
  | Invoices | 346 | 0 (cards) | 114 | 0 (cards) | 90 |
  | Clients | 474 | 0 (cards) | 242 | 0 (cards) | 218 |
  | Team | 296 | 0 (cards) | 64 | 0 (cards) | 40 |
  | Dashboard queue | 176 / 254 | 0 (cards) | 0 / 22 | 0 (cards) | 0 |

  From 1024 to about 1180 px the table still scrolls inside its box (the
  sidebar takes 256 px); from about 1180 px every table fits. The owner chose
  `lg` over `xl` knowing this.
- Clients and Team cards keep the same actions as the table rows (one shared
  render function per screen), so Edit, Archive/Restore, Delete, Deactivate and
  Reactivate are reachable on a phone without a sideways scroll.
- Shots: `shots/2026-11-{before,after}-*.png`.
