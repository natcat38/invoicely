import { useState } from "react";
import { Link, useParams } from "react-router";
import { useAuth } from "@/auth/useAuth";
import { InvoiceDocument } from "@/components/document/InvoiceDocument";
import { Loading, LoadError } from "@/components/Page";
import { StatusBadge } from "@/components/StatusBadge";
import { date, methodLabel, money } from "@/lib/format";
import type { Invoice } from "@/lib/types";
import { InvoiceActions } from "./InvoiceActions";
import { PaymentDialog } from "./PaymentDialog";
import { useInvoice, useInvoicePayments } from "./useInvoice";
import { Card, CardContent } from "@/components/ui/card";

/**
 * One invoice: app chrome framing the document.
 *
 * <p>This is the Design Direction's two layers side by side. The breadcrumb,
 * status badge, stat cards and action buttons are the app — quiet, familiar,
 * indigo. Below them sits the paper the client receives, unchanged from how it
 * would print. Nothing about the document is restyled to fit the page around
 * it; that is the whole point of keeping the two vocabularies apart.
 */
export function InvoiceDetailScreen() {
  const { id } = useParams();
  const invoiceId = Number(id);
  const { session } = useAuth();
  const [payingOpen, setPayingOpen] = useState(false);

  const invoice = useInvoice(invoiceId);
  // Only the owner may read payments (the API answers 403 for staff), so the
  // request is not made at all for staff rather than made and swallowed.
  const isOwner = session!.user.role === "OWNER";
  const payments = useInvoicePayments(invoiceId, isOwner && invoice.isSuccess);

  if (invoice.isPending) return <Loading />;
  if (invoice.isError) return <LoadError error={invoice.error} onRetry={() => invoice.refetch()} />;

  const data = invoice.data;

  return (
    <div className="space-y-6">
      <nav aria-label="Breadcrumb" className="text-sm text-app-muted">
        <Link to="/invoices" className="underline-offset-4 hover:underline">
          Invoices
        </Link>
        <span className="mx-2" aria-hidden="true">
          /
        </span>
        <span className="font-mono text-app-text">{data.number}</span>
      </nav>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-center gap-3">
          <h1 className="font-mono text-2xl font-semibold text-app-text">{data.number}</h1>
          <StatusBadge status={data.status} />
        </div>
        <InvoiceActions
          invoice={data}
          role={session!.user.role}
          onRecordPayment={() => setPayingOpen(true)}
        />
      </header>

      {/* A rejected invoice carries the owner's note back to the drafter. It
          goes above everything else because it is the reason this invoice is
          sitting in DRAFT rather than moving. */}
      {data.status === "DRAFT" && data.rejectionNote ? (
        <div role="status" className="rounded-lg border border-app-warning/40 bg-app-warning-bg p-4">
          <p className="text-sm font-medium text-app-warning">Sent back for changes</p>
          <p className="mt-1 whitespace-pre-line text-sm text-app-text">{data.rejectionNote}</p>
        </div>
      ) : null}

      <StatCards invoice={data} />

      {/* max-w so the document keeps a page-like proportion on a wide monitor
          instead of stretching into a band of text nobody would print. */}
      <div className="max-w-3xl">
        <InvoiceDocument invoice={toDocument(data)} />
      </div>

      {isOwner && payments.data && payments.data.length > 0 ? (
        <section className="max-w-3xl" aria-labelledby="payments-heading">
          <h2 id="payments-heading" className="mb-2 text-sm font-medium text-app-text">
            Payments
          </h2>
          <Card>
            <CardContent className="divide-y divide-app-border p-0">
              {payments.data.map((payment) => (
                <div key={payment.id} className="flex flex-wrap items-baseline justify-between gap-2 p-4">
                  <div>
                    <p className="font-mono text-app-text">{money(payment.amount)}</p>
                    <p className="text-sm text-app-muted">
                      {methodLabel(payment.method)} · {date(payment.paidAt)} · recorded by{" "}
                      {payment.recordedBy.name}
                    </p>
                    {payment.note ? (
                      <p className="mt-1 text-sm text-app-muted">{payment.note}</p>
                    ) : null}
                  </div>
                </div>
              ))}
            </CardContent>
          </Card>
        </section>
      ) : null}

      {isOwner ? (
        <PaymentDialog invoice={data} open={payingOpen} onOpenChange={setPayingOpen} />
      ) : null}
    </div>
  );
}

/**
 * Total, paid and balance as app-layer stat cards.
 *
 * <p>The same three figures appear on the document below, and that repetition
 * is deliberate: the document shows them as the client will read them, while
 * these are the working numbers an owner scans before deciding what to do.
 */
function StatCards({ invoice }: { invoice: Invoice }) {
  return (
    <div className="grid gap-4 sm:grid-cols-3">
      <Stat label="Total" value={money(invoice.total)} />
      <Stat label="Paid" value={money(invoice.amountPaid)} />
      <Stat
        label="Balance due"
        value={money(invoice.balance)}
        // The one number on this screen worth colouring: an outstanding
        // balance on an overdue invoice is the thing the owner opened the page
        // to find out about.
        tone={invoice.balance > 0 && invoice.status === "OVERDUE" ? "overdue" : "normal"}
      />
    </div>
  );
}

function Stat({
  label,
  value,
  tone = "normal",
}: {
  label: string;
  value: string;
  tone?: "normal" | "overdue";
}) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="text-sm text-app-muted">{label}</p>
        <p
          className={`mt-1 font-mono text-xl ${
            tone === "overdue" ? "text-stamp-overdue" : "text-app-text"
          }`}
        >
          {value}
        </p>
      </CardContent>
    </Card>
  );
}

/**
 * The API's invoice, as the document's view model.
 *
 * <p>A translation rather than passing `Invoice` straight through, because the
 * builder feeds the same component from unsaved form state — the document must
 * not depend on a shape only the server can produce.
 */
export function toDocument(invoice: Invoice) {
  return {
    number: invoice.number,
    status: invoice.status,
    issueDate: invoice.issueDate,
    dueDate: invoice.dueDate,
    business: invoice.business,
    client: invoice.client,
    lines: invoice.lineItems.map((line) => ({
      description: line.description,
      quantity: line.quantity,
      unitPrice: line.unitPrice,
      lineTotal: line.lineTotal,
    })),
    subtotal: invoice.subtotal,
    gstRate: invoice.gstRate,
    gst: invoice.gst,
    total: invoice.total,
    amountPaid: invoice.amountPaid,
    balance: invoice.balance,
  };
}
