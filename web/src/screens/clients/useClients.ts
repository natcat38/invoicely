import { useQueryClient } from "@tanstack/react-query";
import { useApiMutation, useApiQuery, keys } from "@/lib/hooks";
import type { Client, Page } from "@/lib/types";

/** Everything the Clients screen can filter or page by. */
export type ClientListParams = {
  archived: boolean;
  q: string;
  page: number;
};

const PAGE_SIZE = 20;

/**
 * `?archived=false&q=...&page=0&size=20`.
 *
 * <p>Built the same way for the request and the query key below — the two
 * must always agree, or a search and a plain page load could end up sharing a
 * cache entry that belongs to a different filter.
 */
function toQueryString({ archived, q, page }: ClientListParams): string {
  const params = new URLSearchParams({
    archived: String(archived),
    page: String(page),
    size: String(PAGE_SIZE),
  });
  // An empty search box means "no filter", not "match the empty string" — so
  // it is left out entirely rather than sent as `q=`. That keeps the query
  // string (and the cache key built from it) identical whether the box was
  // never touched or was typed into and cleared back out.
  const trimmed = q.trim();
  if (trimmed.length > 0) params.set("q", trimmed);
  return params.toString();
}

/**
 * Matches every cached `/clients` list, whatever filter produced it.
 *
 * <p>`keys.clients(qs)` bakes the whole query string into the key, which is
 * right for the query itself — two different filters must never share a
 * cache entry. But it is wrong for invalidation: `invalidateQueries` matches
 * keys by prefix, so invalidating with one specific `qs` would only refresh
 * that one filter and leave every other one someone has open — the Archived
 * tab, a search from a minute ago — showing stale data. A one-element prefix
 * matches all of them at once.
 */
const ALL_CLIENT_LISTS = ["clients"] as const;

export function useClientsList(params: ClientListParams) {
  const qs = toQueryString(params);
  return useApiQuery<Page<Client>>(keys.clients(qs), `/clients?${qs}`);
}

/** The body of `POST`/`PUT /clients` — every field but the `id`, which the URL already carries. */
export type ClientInput = Omit<Client, "id">;

export function useCreateClient() {
  return useApiMutation<Client, ClientInput>(
    (input) => ({ path: "/clients", options: { method: "POST", body: input } }),
    [ALL_CLIENT_LISTS],
  );
}

/**
 * A full replace of one client.
 *
 * <p>Used for ordinary edits, and — by sending the whole client back with
 * `archived` flipped — for archiving and restoring too. There is no separate
 * archive endpoint; Product Scope §5.2 makes this `PUT` do both jobs.
 */
export function useUpdateClient() {
  const queryClient = useQueryClient();
  return useApiMutation<Client, { id: number; input: ClientInput }>(
    ({ id, input }) => ({ path: `/clients/${id}`, options: { method: "PUT", body: input } }),
    [ALL_CLIENT_LISTS],
    {
      onSuccess: (client) => {
        // ALL_CLIENT_LISTS above already refreshes every list. This covers
        // the one cache entry that invalidation cannot reach, because it is
        // keyed by this client's own id rather than by "clients".
        queryClient.invalidateQueries({ queryKey: keys.client(client.id) });
      },
    },
  );
}

export function useDeleteClient() {
  return useApiMutation<void, number>(
    (id) => ({ path: `/clients/${id}`, options: { method: "DELETE" } }),
    [ALL_CLIENT_LISTS],
  );
}
