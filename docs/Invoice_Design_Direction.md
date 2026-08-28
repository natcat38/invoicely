# Invoicely — Design Direction (Phase 2 UI)

The subject is the paper invoice: a document with a number, ruled rows, a sum, and a stamp. The UI borrows the document's own vernacular instead of generic dashboard chrome. Audience: the freelancer themself (daily tool) and, implicitly, recruiters skimming screenshots — the invoice detail screen is the money shot.

## Tokens

**Color** — cool paper and ink, one stamp accent:
- `--paper: #FAFBFC` (background)
- `--ink: #1B2733` (primary text — ink blue-black, not pure black)
- `--rule: #D6DEE6` (ledger rules, borders)
- `--accent: #1E5AA8` (actions, links — fountain-pen blue)
- `--stamp-red: #C0392B` (OVERDUE only)
- `--stamp-green: #1E7B4F` (PAID only)

Dark mode: invert paper/ink (`#141A21` / `#E8EDF2`), keep both stamps.

**Type**
- Display/headings: **Fraunces** (a warm, slightly old-style serif — the letterhead voice), used sparingly: page titles and the invoice number.
- Body/UI: **Inter**, sentence case throughout.
- Numbers: **JetBrains Mono** with `font-variant-numeric: tabular-nums` for every amount, quantity, and date — columns of money must align like a ledger.

**Layout.** Single centered column, max-width 960px, generous whitespace. Tables use horizontal ledger rules only (no vertical lines, no zebra stripes). Left-align text, right-align numbers, always.

**Signature element — the stamp.** Invoice status is rendered as a rubber stamp: uppercase letterspaced text in a 1.5px border, rotated −3°, slightly textured opacity, in stamp-red (OVERDUE) or stamp-green (PAID); DRAFT and SENT are quiet ink-outline badges, unrotated — only terminal/alarming states earn the stamp. It appears on the invoice detail header and as a small version in list rows. This is the one memorable device; everything else stays disciplined.

## Screens

- **Invoice detail** is composed *as the invoice*: Fraunces `INV-2026-0001` top-left, client block like an address block, line items as a ruled table, total row emphasized, stamp overlapping the header. Actions (Send, Record payment) sit in a quiet toolbar above the document, not inside it.
- **Invoice list**: dense ledger table — number, client, issue date, due date, amount (right-aligned mono), status badge. Filter tabs by status.
- **Dashboard**: three stat lines (outstanding, overdue count, revenue this month) set as ledger summary rows, not card grids; then the five most recent invoices.
- **Auth**: centered document card on paper background, nothing clever.

## States & motion

- Empty states invite action: `"No invoices yet. Create your first one."`
- Errors use Problem Details detail text verbatim where user-appropriate.
- Motion: one moment only — the stamp "presses" on when an invoice becomes PAID (scale 1.15→1 with a quick ease-out). `prefers-reduced-motion` disables it. No scroll animations, no skeleton shimmer (plain "Loading…" rows).

## Quality floor

Keyboard focus visible on all interactive elements; forms labelled; contrast ≥ 4.5:1 (checked against both themes); responsive to 375px (tables scroll horizontally in a container, page never does).

## Self-check performed

The default temptation for "invoice app" is a SaaS dashboard: sidebar, stat cards, blue gradient. Rejected — sidebar replaced by a two-item top nav, stat cards by ledger rows, and the personality spent on the document metaphor + stamp instead. Cream/terracotta and dark/acid-green cliché palettes avoided; this is a cool paper-and-ink scheme derived from the subject.
