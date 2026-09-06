import { useSearchParams } from "react-router";
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
  /**
   * The whole filter — status, client and page — lives in the URL rather than
   * in component state.
   *
   * <p>Three reasons. The dashboard links straight to the approval queue
   * (`/invoices?status=PENDING_APPROVAL`), and a link that silently landed on
   * the unfiltered list would be worse than no link. A filtered view becomes
   * something the owner can bookmark or send to someone. And keeping all of
   * it in one place is what makes Back and Forward coherent: with the status
   * in the URL but the page in `useState`, going forward into a filtered view
   * restored the filter while leaving the page number from wherever you had
   * been, which showed an empty page 3 of a filter you had just arrived at.
   *
   * <p>Every value is validated on the way out of the URL — an unrecognised
   * status falls back to "ALL", a non-numeric page to 0 — so a hand-edited or
   * stale link degrades to something sensible instead of a 400.
   */
  const [searchParams, setSearchParams] = useSearchParams();

  const statusParam = searchParams.get("status");
  const status: StatusFilter =
    statusParam !== null && (STATUS_FILTERS as string[]).includes(statusParam)
      ? (statusParam as StatusFilter)
      : "ALL";

  const clientIdParam = Number(searchParams.get("clientId"));
  const clientId = Number.isInteger(clientIdParam) && clientIdParam > 0 ? clientIdParam : null;

  const pageParam = Number(searchParams.get("page"));
  const page = Number.isInteger(pageParam) && pageParam > 0 ? pageParam : 0;

  /**
   * Writes the filter back to the URL.
   *
   * <p>`page` is reset by every caller that changes a filter, and doing it
   * here rather than at each call site is what makes that impossible to
   * forget. It also has to happen in the same update as the filter: two
   * separate writes would render once with the new filter and the old page,
   * and that render would send a request for it.
   */
  function updateFilter(change: (params: URLSearchParams) => void, resetPage = true) {
    setSearchParams(
      (current) => {
        const updated = new URLSearchParams(current);
        change(updated);
        if (resetPage) updated.delete("page");
        return updated;
      },
      // `replace` so clicking through five tabs does not leave five entries in
      // the back stack between the user and wherever they came from.
      { replace: true },
    );
  }

  function setPage(next: number) {
    updateFilter((params) => {
      if (next <= 0) params.delete("page");
      else params.set("page", String(next));
    }, false);
  }

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
    updateFilter((params) => {
      if (next === "ALL") params.delete("status");
      else params.set("status", next);
    });
  }

  function setClientId(next: number | null) {
    updateFilter((params) => {
      if (next === null) params.delete("clientId");
      else params.set("clientId", String(next));
    });
  }

  const hasFilters = status !== "ALL" || clientId !== null;

  function clearFilters() {
    updateFilter((params) => {
      params.delete("status");
      params.delete("clientId");
    });
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
