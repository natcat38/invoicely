import { Link } from "react-router";
import { StatusBadge } from "@/components/StatusBadge";
import { date, money } from "@/lib/format";
import type { InvoiceSummary } from "@/lib/types";

/**
 * Invoice rows as cards, for phones.
 *
 * <p>Six table columns do not fit a 390px screen, and a table that scrolls
 * sideways hides the two things an owner checks first: the total and the due
 * date. Below `sm` each invoice is a card instead (number and status, client,
 * total and due date), and from `sm` the screens show their table. Both are in
 * the page; CSS decides which one shows.
 *
 * <p>The number is the card's one link, stretched over the whole card with
 * `after:absolute after:inset-0`, so the card is a big tap target and a
 * keyboard user still tabs to exactly one thing per invoice.
 */
export function InvoiceCards({ invoices }: { invoices: InvoiceSummary[] }) {
  return (
    <ul className="space-y-2 sm:hidden">
      {invoices.map((invoice) => (
        <li
          key={invoice.id}
          className="relative rounded-lg border border-app-border bg-app-surface p-4 focus-within:ring-2 focus-within:ring-app-accent/50"
        >
          <div className="flex items-center justify-between gap-3">
            <Link
              to={`/invoices/${invoice.id}`}
              className="font-mono text-app-accent after:absolute after:inset-0"
            >
              {invoice.number}
            </Link>
            <StatusBadge status={invoice.status} />
          </div>
          <p className="mt-1 truncate text-sm text-app-text">{invoice.clientName}</p>
          <div className="mt-3 flex items-baseline justify-between gap-3">
            <span className="font-mono text-app-text">{money(invoice.total)}</span>
            <span className="text-sm text-app-muted">Due {date(invoice.dueDate)}</span>
          </div>
          {/* Only worth a line when part of it has been paid and part is still owed. */}
          {invoice.balance > 0 && invoice.balance !== invoice.total ? (
            <p className="mt-1 text-sm text-app-muted">Balance due {money(invoice.balance)}</p>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
