import { Link, useNavigate } from "react-router";
import { EmptyState, LoadError, Loading, PageHeader } from "@/components/Page";
import { StatusBadge } from "@/components/StatusBadge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { amount, date, statusLabel } from "@/lib/format";
import { STATUS_FILTERS, useInvoiceList, type StatusFilter } from "./useInvoiceList";

/**
 * The invoice ledger — Product Scope §5.3.
 *
 * <p>The server's default sort is already "needs attention first" (overdue,
 * then awaiting approval, then newest), which is what makes this a to-do list
 * rather than an archive. Nothing here re-sorts the rows: doing that on the
 * client would fight the server's ordering and desync from its pagination,
 * since page 2 would no longer be "whatever page 2 means under this sort".
 */
export function InvoiceListScreen() {
  const {
    invoices,
    clients,
    status,
    setStatus,
    clientId,
    setClientId,
    page,
    setPage,
    hasFilters,
    clearFilters,
  } = useInvoiceList();
  const navigate = useNavigate();

  return (
    <div>
      <PageHeader
        title="Invoices"
        actions={
          <Button asChild>
            <Link to="/invoices/new">New invoice</Link>
          </Button>
        }
      />

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <Tabs value={status} onValueChange={(value) => setStatus(value as StatusFilter)}>
          <TabsList>
            {STATUS_FILTERS.map((value) => (
              <TabsTrigger key={value} value={value}>
                {value === "ALL" ? "All" : statusLabel(value)}
              </TabsTrigger>
            ))}
          </TabsList>
        </Tabs>

        <Select
          // "" isn't a valid Radix Select value (it means "unset"), so "all"
          // stands in for "no client filter" and is translated back to null
          // at the boundary.
          value={clientId === null ? "all" : String(clientId)}
          onValueChange={(value) => setClientId(value === "all" ? null : Number(value))}
          disabled={clients.isPending}
        >
          <SelectTrigger aria-label="Filter by client" className="w-48">
            <SelectValue placeholder="All clients" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All clients</SelectItem>
            {clients.data?.content.map((client) => (
              <SelectItem key={client.id} value={String(client.id)}>
                {client.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      {invoices.isPending ? <Loading /> : null}

      {invoices.isError ? (
        <LoadError error={invoices.error} onRetry={() => invoices.refetch()} />
      ) : null}

      {invoices.data && invoices.data.content.length === 0 ? (
        hasFilters ? (
          <EmptyState
            title="No invoices match this filter."
            description="Try a different status or client."
            action={
              <Button variant="outline" size="sm" onClick={clearFilters}>
                Clear filters
              </Button>
            }
          />
        ) : (
          <EmptyState
            title="No invoices yet."
            description="Create your first one."
            action={
              <Button asChild size="sm">
                <Link to="/invoices/new">New invoice</Link>
              </Button>
            }
          />
        )
      ) : null}

      {invoices.data && invoices.data.content.length > 0 ? (
        <>
          {/* The Table component already scrolls its own overflow (see
              ui/table.tsx); the min-width is what keeps six columns from
              collapsing into unreadable slivers at 375px instead of just
              scrolling. */}
          <Table className="min-w-[720px]">
            <TableHeader>
              <TableRow>
                <TableHead>Number</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Client</TableHead>
                <TableHead className="text-right">Total</TableHead>
                <TableHead className="text-right">Balance due</TableHead>
                <TableHead>Due date</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {invoices.data.content.map((invoice) => (
                <TableRow
                  key={invoice.id}
                  className="cursor-pointer"
                  // Convenience for a mouse click anywhere else in the row.
                  // The <Link> below is what actually makes the row reachable
                  // by keyboard and middle-clickable — an onClick on the <tr>
                  // alone would offer neither.
                  onClick={() => navigate(`/invoices/${invoice.id}`)}
                >
                  <TableCell className="font-mono">
                    <Link
                      to={`/invoices/${invoice.id}`}
                      className="text-app-accent underline-offset-4 hover:underline"
                      // Without this, clicking the link also fires the row's
                      // onClick above and pushes the same destination onto
                      // history twice.
                      onClick={(event) => event.stopPropagation()}
                    >
                      {invoice.number}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <StatusBadge status={invoice.status} />
                  </TableCell>
                  <TableCell>{invoice.clientName}</TableCell>
                  <TableCell className="text-right font-mono">{amount(invoice.total)}</TableCell>
                  <TableCell className="text-right font-mono">{amount(invoice.balance)}</TableCell>
                  <TableCell>{date(invoice.dueDate)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>

          {invoices.data.totalPages > 1 ? (
            <div className="mt-4 flex items-center justify-between">
              <Button
                variant="outline"
                size="sm"
                disabled={invoices.data.first}
                onClick={() => setPage(page - 1)}
              >
                Previous
              </Button>
              <p className="text-sm text-app-muted">
                Page {invoices.data.number + 1} of {invoices.data.totalPages}
              </p>
              <Button
                variant="outline"
                size="sm"
                disabled={invoices.data.last}
                onClick={() => setPage(page + 1)}
              >
                Next
              </Button>
            </div>
          ) : null}
        </>
      ) : null}
    </div>
  );
}
