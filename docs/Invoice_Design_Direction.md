# Invoicely — Design Direction (Phase 2 UI)

**Core decision (owner: Natalie, 2026-08-28, chosen from 3 prototyped variants):**
the product has two visual layers with different jobs —

1. **The app** — a modern SaaS workspace where the business builds and manages
   invoices. Sidebar navigation, panels, stat cards. Familiar, efficient.
2. **The document** — the invoice as the client sees it: a paper-and-ink
   artifact rendered inside the app as a live preview, and later exportable.
   All "invoice personality" (serif number, ledger rules, status stamp) lives
   here, never in the app chrome.

The contrast is the signature: quiet SaaS chrome framing a crafted paper
document. The document looks the same wherever it appears.

## App layer tokens (variant B)

- Background `#F8F9FB`, surface `#FFFFFF`, border `#E2E8F0`, text `#0F172A`,
  muted `#64748B`, sidebar `#0F172A`, accent `#4F46E5` (primary actions),
  warning badge amber, paid badge green.
- Type: **Inter** throughout; **JetBrains Mono** with `tabular-nums` for all
  amounts in cards and tables.
- Layout: fixed dark sidebar (Dashboard, Invoices, Clients, Team, Settings),
  content area with breadcrumb + page header + actions right-aligned.
- Components lean on shadcn/ui patterns (cards, badges, tables, dialogs).

## Document layer tokens (variant A)

- Paper `#FFFFFF` on app background, hairline border `#D6DEE6`, ink `#1B2733`,
  fountain-pen blue `#1E5AA8`, stamp red `#C0392B` (OVERDUE), stamp green
  `#1E7B4F` (PAID).
- Type: **Fraunces** for the invoice number, Inter for body, JetBrains Mono
  tabular for all figures.
- Ruled ledger table: horizontal rules only; text left, numbers right.
- Totals block: subtotal → GST 9% → total SGD → payments → balance due.
- **The stamp**: rotated −3°, letterspaced mono, red OVERDUE / green PAID on
  the document only; DRAFT and SENT are quiet outline badges in the app chrome.

## Where each layer appears

- Invoice **detail** page: app chrome (breadcrumb, status badge, actions,
  stat cards for total/paid/balance) framing the document preview below.
- Invoice **builder** (DRAFT): app-layer form panels on the left/top; the
  document preview updates live as line items change — the maker sees what the
  client will get. Staff see "Submit for approval"; owner sees "Approve & send".
- Client-facing rendering (v2: PDF export/public link) reuses the document
  layer unchanged.

## States & motion

- Empty states invite action ("No invoices yet. Create your first one.").
- Errors surface Problem Details text where user-appropriate.
- One motion moment: the stamp presses onto the document when an invoice
  becomes PAID (scale 1.15→1, ease-out); `prefers-reduced-motion` disables it.
  No scroll animations; plain "Loading…" rows, no skeleton shimmer.

## Quality floor

Visible keyboard focus, labelled forms, contrast ≥ 4.5:1, responsive to 375px
(tables scroll inside their container; the page never scrolls horizontally).

## Provenance

Chosen from three throwaway mockups
(`invoice-project-docs/mockups/prototype-invoice-detail.html`, kept locally):
A paper-document, B SaaS dashboard, C terminal ledger. Decision: B for the
app, A for the document, C rejected. Dark-mode app theme deferred to polish.
