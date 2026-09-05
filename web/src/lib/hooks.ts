import { useCallback, useEffect, useState } from "react";
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationOptions,
  type UseQueryOptions,
} from "@tanstack/react-query";
import { ApiError, api, type RequestOptions } from "@/lib/api";
import { useAuth } from "@/auth/useAuth";

/**
 * The bridge between the session and the API client.
 *
 * <p>Two jobs, both of which would otherwise be repeated at every call site:
 * attach the caller's token, and act on the answer when the API says that
 * token is finished. Task 7b built `ApiError.requiresReauthentication` for
 * the second job but had no screen that fetched anything; this is where it
 * finally gets used.
 */
export function useApiClient() {
  const { session, signOut } = useAuth();
  const token = session?.token ?? null;

  return useCallback(
    async <T,>(path: string, options: Omit<RequestOptions, "token"> = {}): Promise<T> => {
      try {
        return await api<T>(path, { ...options, token });
      } catch (error) {
        // An expired token, one superseded by a password change, or a
        // deactivated account: none of these get better by retrying, and
        // leaving the user on a screen that quietly fails every request is
        // worse than putting them back on the login page. Signing out also
        // clears the query cache, so nothing stale survives into the next
        // session.
        if (error instanceof ApiError && error.requiresReauthentication) signOut();
        throw error;
      }
    },
    [token, signOut],
  );
}

/**
 * A read.
 *
 * <p>The query key must name every input that changes the answer — the path
 * here — or two different requests would share one cache entry and the second
 * screen would render the first one's data.
 */
export function useApiQuery<T>(
  key: readonly unknown[],
  path: string,
  options?: Omit<UseQueryOptions<T, ApiError>, "queryKey" | "queryFn">,
) {
  const request = useApiClient();
  return useQuery<T, ApiError>({
    queryKey: key,
    queryFn: ({ signal }) => request<T>(path, { signal }),
    ...options,
  });
}

/**
 * A write, with the invalidation that has to follow it.
 *
 * <p>`invalidates` lists the query-key prefixes this mutation makes stale.
 * Sending an invoice changes the invoice, the list it appears in and the
 * dashboard totals; forgetting one of those is how a screen ends up showing
 * a status the server no longer agrees with. Passing them here keeps the
 * decision next to the mutation that caused it.
 */
export function useApiMutation<TResult, TInput>(
  mutate: (input: TInput) => { path: string; options?: Omit<RequestOptions, "token"> },
  invalidates: readonly (readonly unknown[])[] = [],
  options?: Omit<UseMutationOptions<TResult, ApiError, TInput>, "mutationFn">,
) {
  const request = useApiClient();
  const queryClient = useQueryClient();

  return useMutation<TResult, ApiError, TInput>({
    mutationFn: (input) => {
      const { path, options: requestOptions } = mutate(input);
      return request<TResult>(path, requestOptions);
    },
    ...options,
    // Forwarded as a rest argument rather than named ones: TanStack has
    // changed this callback's arity between minor versions, and a caller's
    // own onSuccess should keep receiving whatever the library passes without
    // this wrapper needing an edit each time.
    onSuccess: (...args) => {
      for (const key of invalidates) {
        queryClient.invalidateQueries({ queryKey: key });
      }
      options?.onSuccess?.(...args);
    },
  });
}

/** Query keys in one place, so a typo cannot silently create a second cache entry. */
export const keys = {
  clients: (params?: string) => ["clients", params ?? ""] as const,
  client: (id: number) => ["clients", "detail", id] as const,
  invoices: (params?: string) => ["invoices", params ?? ""] as const,
  invoice: (id: number) => ["invoices", "detail", id] as const,
  payments: (invoiceId: number) => ["invoices", "detail", invoiceId, "payments"] as const,
  dashboard: () => ["dashboard"] as const,
} as const;

/**
 * Delays a value, so a search box queries the API when the user stops typing
 * rather than on every keystroke.
 *
 * <p>Without it, "Bright Cafe" is eleven requests, and their responses can
 * arrive out of order — the list would flicker and could settle on the
 * results for "Bright Caf".
 */
export function useDebounced<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
