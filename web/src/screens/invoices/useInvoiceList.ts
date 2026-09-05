import { useState } from "react";
import { useApiQuery, keys } from "@/lib/hooks";
import type { Client, InvoiceStatus, InvoiceSummary, Page } from "@/lib/types";

/** Matches the `size=20` the API is documented to expect for this list. */
const PAGE_SIZE = 20;

/**
 * The tab strip's values. "ALL" is a UI-only sentinel — the API's "everything"
 * view is spelled by *omitting* `status` from the query string, not by a
 * status value of its own.
 */
export type StatusFilter = InvoiceStatus | "ALL";

/**
 * Every tab, in the order Product Scope §5.3 lists them. This happens to be
 * the same lifecycle order `InvoiceStatus` is declared in (`types.ts`), so it
 * is derived rather than retyped — a sixth status added there would otherwise
 * have to be remembered here too.
 */
export const STATUS_FILTERS: StatusFilter[] = [
  "ALL",
  "DRAFT",
  "PENDING_APPROVAL",
  "SENT",
  "OVERDUE",
  "PAID",
];

/**
 * Data and filter state behind the invoice list screen.
 *
 * <p>Pulled out of the component so the screen only has to render, and so the
 * one rule that matters — changing a filter has to reset `page` back to 0 —
 * lives in a single place instead of being re-remembered at every setter call
 * site. Landing a filter on, say, page 3 of the previous view would otherwise
 * show an empty list with no visible reason, because "Overdue" almost never
 * has as many pages as "All".
 */
export function useInvoiceList() {
  const [status, setStatusState] = useState<StatusFilter>("ALL");
  const [clientId, setClientIdState] = useState<number | null>(null);
  const [page, setPage] = useState(0);

  const params = new URLSearchParams();
  if (status !== "ALL") params.set("status", status);
  if (clientId !== null) params.set("clientId", String(clientId));
  params.set("page", String(page));
  params.set("size", String(PAGE_SIZE));
  const queryString = params.toString();

  const invoices = useApiQuery<Page<InvoiceSummary>>(
    keys.invoices(queryString),
    `/invoices?${queryString}`,
  );

  // Options for the client filter, not the ledger itself: archived clients
  // are excluded because you cannot file a new invoice under one, and 200 is
  // comfortably above any real business's active client count, so the
  // dropdown never needs its own pagination.
  const clients = useApiQuery<Page<Client>>(
    keys.clients("archived=false&size=200"),
    "/clients?archived=false&size=200",
  );

  function setStatus(next: StatusFilter) {
    setStatusState(next);
    setPage(0);
  }

  function setClientId(next: number | null) {
    setClientIdState(next);
    setPage(0);
  }

  const hasFilters = status !== "ALL" || clientId !== null;

  function clearFilters() {
    setStatusState("ALL");
    setClientIdState(null);
    setPage(0);
  }

  return {
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
  };
}
