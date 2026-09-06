import { Link } from "react-router";
import { EmptyState, LoadError, Loading, PageHeader } from "@/components/Page";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { amount, date, money } from "@/lib/format";
import { keys, useApiQuery } from "@/lib/hooks";
import type { Dashboard, InvoiceSummary, Page } from "@/lib/types";

/**
 * The owner's landing page: what is owed, what is late, what came in this
 * month, and what is waiting on them.
 *
 * <p>Owner-only, and the route is already gated — staff land on the invoice
 * list instead, because the API answers 403 for every figure on this page.
 *
 * <p>The approval queue is the reason this screen is the owner's home rather
 * than a report. Product Scope §2 makes maker-checker the point of the
 * product: staff draft, the owner approves. Anything sitting in that queue is
 * work that has stopped until this person acts, so it is not tucked below the
 * money — it sits directly under it, with a way straight into each invoice.
 */
const RECENT_QUERY = "page=0&size=5";

export function DashboardScreen() {
  const dashboard = useApiQuery<Dashboard>(keys.dashboard(), "/dashboard");

  /**
   * The five most recent invoices, from the ordinary list endpoint rather
   * than the dashboard's own response.
   *
   * <p>`/dashboard` deliberately returns only the approval queue, and adding
   * a second list to it would mean the API deciding what "recent" means for
   * one screen. The list endpoint already sorts by "needs attention" — the
   * same order the Invoices page shows — so this is the top of that list, and
   * the link below goes to the rest of it.
   */
  const recent = useApiQuery<Page<InvoiceSummary>>(
    keys.invoices(RECENT_QUERY),
    `/invoices?${RECENT_QUERY}`,
  );

  if (dashboard.isPending) return <Loading label="Loading your dashboard…" />;
  if (dashboard.isError) {
    return <LoadError error={dashboard.error} onRetry={() => dashboard.refetch()} />;
  }

  const data = dashboard.data;

  return (
    <div>
      <PageHeader
        title="Dashboard"
        actions={
          <Button asChild>
            <Link to="/invoices/new">New invoice</Link>
          </Button>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <Stat
          label="Outstanding"
          value={money(data.outstandingTotal)}
          note="Across every sent and unpaid invoice"
        />
        <Stat
          label="Overdue"
          value={money(data.overdueAmount)}
          // The count carries as much weight as the amount here: one large
          // late invoice and nine small ones are the same figure and
          // completely different problems.
          note={countLabel(data.overdueCount, "invoice", "invoices")}
          tone={data.overdueCount > 0 ? "overdue" : "normal"}
        />
        <Stat
          label="Revenue this month"
          value={money(data.revenueThisMonth)}
          // Said explicitly because the two are easy to confuse, and an owner
          // reading this as "what I billed" would think they had a much worse
          // month than they did.
          note="Payments received, not invoiced"
        />
      </div>

      <section className="mt-8" aria-labelledby="approval-queue">
        <div className="mb-3 flex items-baseline justify-between gap-4">
          <h2 id="approval-queue" className="text-lg font-medium text-app-text">
            Awaiting your approval
          </h2>
          {data.awaitingApprovalCount > 0 ? (
            <Link
              to="/invoices?status=PENDING_APPROVAL"
              className="text-sm font-medium text-app-accent underline-offset-4 hover:underline"
            >
              See all {data.awaitingApprovalCount}
            </Link>
          ) : null}
        </div>

        {data.awaitingApprovalQueue.length === 0 ? (
          <EmptyState
            title="Nothing waiting on you."
            description="Invoices your staff submit for approval will appear here."
          />
        ) : (
          <InvoiceRows invoices={data.awaitingApprovalQueue} />
        )}
      </section>

      <section className="mt-8" aria-labelledby="recent-invoices">
        <div className="mb-3 flex items-baseline justify-between gap-4">
          <h2 id="recent-invoices" className="text-lg font-medium text-app-text">
            Recent invoices
          </h2>
          <Link
            to="/invoices"
            className="text-sm font-medium text-app-accent underline-offset-4 hover:underline"
          >
            See all
          </Link>
        </div>

        {/* No LoadError here: the dashboard's own figures are the point of
            this page, and a failed side-list should not replace them with an
            error. It simply shows nothing, and the Invoices page reports the
            failure properly if it persists. */}
        {recent.isPending ? (
          <Loading label="Loading invoices…" />
        ) : recent.data && recent.data.content.length > 0 ? (
          <InvoiceRows invoices={recent.data.content} />
        ) : recent.isSuccess ? (
          <EmptyState
            title="No invoices yet."
            description="Create your first one."
            action={
              <Button asChild size="sm">
                <Link to="/invoices/new">New invoice</Link>
              </Button>
            }
          />
        ) : null}
      </section>
    </div>
  );
}

/** The same five columns for both lists on this page, so they read as one thing. */
function InvoiceRows({ invoices }: { invoices: InvoiceSummary[] }) {
  return (
    // The table scrolls inside this container rather than widening the page —
    // the quality floor asks that the page itself never scroll sideways.
    <div className="overflow-x-auto">
      <Table className="min-w-[560px]">
        <TableHeader>
          <TableRow>
            <TableHead>Number</TableHead>
            <TableHead>Client</TableHead>
            <TableHead className="text-right">Total</TableHead>
            <TableHead>Due date</TableHead>
            <TableHead>Status</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {invoices.map((invoice) => (
            <TableRow key={invoice.id}>
              <TableCell>
                {/* A real link, so the row can be opened with the keyboard,
                    middle-clicked into a new tab, and read by a screen reader
                    as the thing it is. */}
                <Link
                  to={`/invoices/${invoice.id}`}
                  className="font-mono text-app-accent underline-offset-4 hover:underline"
                >
                  {invoice.number}
                </Link>
              </TableCell>
              <TableCell>{invoice.clientName}</TableCell>
              <TableCell className="text-right font-mono">{amount(invoice.total)}</TableCell>
              <TableCell className="font-mono">{date(invoice.dueDate)}</TableCell>
              <TableCell>
                <StatusBadge status={invoice.status} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function Stat({
  label,
  value,
  note,
  tone = "normal",
}: {
  label: string;
  value: string;
  note?: string;
  tone?: "normal" | "overdue";
}) {
  return (
    <Card>
      <CardContent className="p-5">
        <p className="text-sm text-app-muted">{label}</p>
        <p
          className={`mt-1 font-mono text-2xl ${
            tone === "overdue" ? "text-stamp-overdue" : "text-app-text"
          }`}
        >
          {value}
        </p>
        {note ? <p className="mt-1 text-xs text-app-muted">{note}</p> : null}
      </CardContent>
    </Card>
  );
}

/** `1 invoice` / `4 invoices` — the plural handled once rather than at each call. */
function countLabel(count: number, singular: string, plural: string): string {
  return `${count} ${count === 1 ? singular : plural}`;
}
