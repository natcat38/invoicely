import { amount, date, money, quantity as formatQuantity } from "@/lib/format";
import type { InvoiceStatus } from "@/lib/types";
import { Stamp } from "./Stamp";

/**
 * The invoice as the client sees it — the paper-and-ink artifact the whole
 * product is built around.
 *
 * <p>This is the Design Direction's *document layer*, and it obeys different
 * rules from every other component in the app. The app chrome is quiet on
 * purpose so that this can carry the personality: a serif invoice number,
 * ruled ledger lines, figures in tabular mono, and a rotated stamp when the
 * invoice is overdue or paid. None of the app's indigo appears here, and none
 * of this ink appears out there — that contrast is the signature, and it only
 * survives if neither layer borrows from the other.
 *
 * <p>It takes a plain view model rather than an `Invoice`, because the builder
 * renders it live from unsaved form state while the detail page renders it
 * from the API's response. Both produce the same document; if this component
 * knew about `Invoice` the builder would have to fake one.
 */
export type InvoiceDocumentModel = {
  number: string;
  status: InvoiceStatus;
  issueDate: string;
  dueDate: string;
  business: { name: string; address: string | null; uen: string | null; gstRegistered: boolean };
  client: {
    name: string;
    address: string | null;
    contactPerson: string | null;
    email: string | null;
    uen: string | null;
    paymentNotes: string | null;
  };
  lines: { description: string; quantity: number; unitPrice: number; lineTotal: number }[];
  subtotal: number;
  /** Null means this business charges no GST: the line is omitted, not zeroed. */
  gstRate: number | null;
  gst: number;
  total: number;
  amountPaid: number;
  balance: number;
};

export function InvoiceDocument({ invoice }: { invoice: InvoiceDocumentModel }) {
  const hasGst = invoice.gstRate !== null;

  return (
    // `relative` anchors the stamp; the hairline border and white paper lift
    // the document off the app's off-white page without a drop shadow, which
    // would read as a card in the app layer rather than as a sheet of paper.
    <article
      className="relative overflow-hidden border border-paper-rule bg-paper p-8 text-ink sm:p-10"
      aria-label={`Invoice ${invoice.number}`}
    >
      <header className="flex flex-wrap items-start justify-between gap-6">
        <div>
          {/* The letterhead. Every field below the name is optional, and each
              is omitted rather than printed empty — a document with a blank
              line where an address should be looks broken, where one without
              the line just looks plainer (ADR-0011). */}
          <p className="text-lg font-semibold">{invoice.business.name}</p>
          {invoice.business.address ? (
            <p className="mt-1 max-w-xs whitespace-pre-line text-sm text-ink/70">
              {invoice.business.address}
            </p>
          ) : null}
          {invoice.business.uen ? (
            <p className="mt-1 text-sm text-ink/70">
              {/* Labelled "GST Reg. No." only when the business actually
                  charges GST; otherwise the same number is just its UEN.
                  Printing "GST Reg. No." for an unregistered business would
                  be a false claim on a tax document. */}
              {invoice.business.gstRegistered ? "GST Reg. No." : "UEN"}{" "}
              <span className="font-mono">{invoice.business.uen}</span>
            </p>
          ) : null}
        </div>

        <div className="text-right">
          <p className="text-xs uppercase tracking-[0.2em] text-ink/50">Invoice</p>
          {/* The one place Fraunces appears in the product. */}
          <p className="font-serif text-3xl leading-tight text-ink">{invoice.number}</p>
        </div>
      </header>

      <div className="mt-8 flex flex-wrap justify-between gap-6">
        <div>
          <p className="text-xs uppercase tracking-[0.16em] text-ink/50">Bill to</p>
          <p className="mt-1 font-medium">{invoice.client.name || "—"}</p>
          {invoice.client.contactPerson ? (
            <p className="text-sm text-ink/70">{invoice.client.contactPerson}</p>
          ) : null}
          {invoice.client.address ? (
            <p className="mt-1 max-w-xs whitespace-pre-line text-sm text-ink/70">
              {invoice.client.address}
            </p>
          ) : null}
          {invoice.client.email ? (
            <p className="mt-1 text-sm text-ink/70">{invoice.client.email}</p>
          ) : null}
          {invoice.client.uen ? (
            <p className="mt-1 text-sm text-ink/70">
              UEN <span className="font-mono">{invoice.client.uen}</span>
            </p>
          ) : null}
        </div>

        <dl className="text-sm">
          <div className="flex justify-between gap-8">
            <dt className="text-ink/50">Issue date</dt>
            <dd className="font-mono">{date(invoice.issueDate)}</dd>
          </div>
          <div className="mt-1 flex justify-between gap-8">
            <dt className="text-ink/50">Due date</dt>
            <dd className="font-mono">{date(invoice.dueDate)}</dd>
          </div>
        </dl>
      </div>

      {/* The ledger. Horizontal rules only, per the Design Direction —
          vertical borders would turn it into a spreadsheet. Wrapped so a long
          description scrolls the table rather than the page on a phone. */}
      <div className="mt-8 overflow-x-auto">
        <table className="w-full min-w-[30rem] border-collapse text-sm">
          <thead>
            <tr className="border-b border-ink/30 text-left text-xs uppercase tracking-[0.12em] text-ink/50">
              <th scope="col" className="pb-2 font-medium">Description</th>
              <th scope="col" className="pb-2 text-right font-medium">Qty</th>
              <th scope="col" className="pb-2 text-right font-medium">Unit price</th>
              <th scope="col" className="pb-2 text-right font-medium">Amount</th>
            </tr>
          </thead>
          <tbody>
            {invoice.lines.length === 0 ? (
              <tr className="border-b border-paper-rule">
                <td colSpan={4} className="py-6 text-center text-ink/40">
                  No lines yet.
                </td>
              </tr>
            ) : (
              invoice.lines.map((line, index) => (
                // Line items have no stable id while a draft is being typed,
                // so the index is the only key available here. It is safe
                // because the rows are never reordered — adding and removing
                // happens at the end of the list.
                <tr key={index} className="border-b border-paper-rule align-top">
                  <td className="py-2 pr-4">{line.description || <span className="text-ink/40">—</span>}</td>
                  <td className="py-2 text-right font-mono">{formatQuantity(line.quantity)}</td>
                  <td className="py-2 text-right font-mono">{amount(line.unitPrice)}</td>
                  <td className="py-2 text-right font-mono">{amount(line.lineTotal)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Totals: subtotal → GST → total → payments → balance due, in the
          Design Direction's order, right-aligned under the Amount column. */}
      {/* The stamp lives here rather than over the header. Anchored to the
          totals row because that row always exists and its left half is always
          empty, whatever the invoice contains — so the stamp lands beside the
          balance (which is what it is telling you about) and can never cover
          the invoice number or a line item, however long the ledger runs. */}
      <div className="relative mt-6 flex justify-end">
        <Stamp status={invoice.status} />
        <dl className="w-full max-w-xs text-sm">
          <Row label="Subtotal" value={amount(invoice.subtotal)} />

          {hasGst ? (
            // The rate is part of the label because a client reading a printed
            // invoice cannot ask what rate was applied. 0.09 → "GST 9%".
            <Row label={`GST ${formatRate(invoice.gstRate!)}`} value={amount(invoice.gst)} />
          ) : null}

          <div className="mt-2 flex justify-between border-t border-ink/30 pt-2 text-base font-semibold">
            <dt>Total SGD</dt>
            <dd className="font-mono">{money(invoice.total)}</dd>
          </div>

          {/* Payments and balance only appear once money has actually been
              received. On an unpaid invoice the total is the amount due, and a
              "Paid S$0.00" line would just be noise on the page. */}
          {invoice.amountPaid > 0 ? (
            <>
              <Row label="Paid" value={`-${amount(invoice.amountPaid)}`} className="mt-2" />
              <div className="mt-1 flex justify-between border-t border-paper-rule pt-2 font-semibold">
                <dt>Balance due</dt>
                <dd className="font-mono">{money(invoice.balance)}</dd>
              </div>
            </>
          ) : null}
        </dl>
      </div>

      {invoice.client.paymentNotes ? (
        <footer className="mt-10 border-t border-paper-rule pt-4">
          <p className="text-xs uppercase tracking-[0.16em] text-ink/50">How to pay</p>
          {/* Free text the business wrote — PayNow or bank details, usually.
              whitespace-pre-line keeps the line breaks they typed, which is
              what makes an account number readable. */}
          <p className="mt-1 whitespace-pre-line text-sm text-ink/80">
            {invoice.client.paymentNotes}
          </p>
        </footer>
      ) : null}
    </article>
  );
}

function Row({
  label,
  value,
  className = "",
}: {
  label: string;
  value: string;
  className?: string;
}) {
  return (
    <div className={`flex justify-between ${className}`}>
      <dt className="text-ink/70">{label}</dt>
      <dd className="font-mono">{value}</dd>
    </div>
  );
}

/**
 * `0.09` → `9%`, and `0.0875` → `8.75%`. Trailing zeros are dropped because
 * "GST 9.00%" reads like a rate that might change tomorrow, while "GST 9%" is
 * how the rate is actually written and spoken.
 */
function formatRate(rate: number): string {
  return `${Number((rate * 100).toFixed(2))}%`;
}
