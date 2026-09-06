import { useApiMutation, useApiQuery, keys } from "@/lib/hooks";
import type { Invoice, InvoiceInput, Payment, PaymentMethod } from "@/lib/types";

/**
 * Everything the detail and builder screens do to one invoice.
 *
 * <p>The invalidation lists matter more than they look. Every lifecycle
 * action changes three things at once: the invoice itself, its row in the
 * "needs attention" list (whose *sort order* depends on status), and the
 * owner's dashboard totals. Missing one leaves a screen showing a status the
 * server no longer agrees with.
 *
 * <p>`ANY_INVOICE` is the bare `["invoices"]` prefix, and one entry covers all
 * of it: TanStack Query invalidates by key *prefix*, so it matches every
 * filtered variant of the list (`["invoices", "?status=PAID"]`), the detail
 * (`["invoices", "detail", 7]`) and that invoice's payments
 * (`["invoices", "detail", 7, "payments"]`). Listing those separately would
 * add nothing but the impression that they were needed. The prefix is what
 * makes it correct that a sent invoice moves between status tabs.
 */

const ANY_INVOICE = ["invoices"] as const;

export function useInvoice(id: number) {
  return useApiQuery<Invoice>(keys.invoice(id), `/invoices/${id}`);
}

export function useInvoicePayments(id: number, enabled: boolean) {
  return useApiQuery<Payment[]>(keys.payments(id), `/invoices/${id}/payments`, { enabled });
}

export function useCreateInvoice() {
  return useApiMutation<Invoice, InvoiceInput>(
    (input) => ({ path: "/invoices", options: { method: "POST", body: input } }),
    [ANY_INVOICE, keys.dashboard()],
  );
}

export function useUpdateInvoice(id: number) {
  return useApiMutation<Invoice, InvoiceInput>(
    (input) => ({ path: `/invoices/${id}`, options: { method: "PUT", body: input } }),
    [ANY_INVOICE, keys.dashboard()],
  );
}

export function useDeleteInvoice(id: number) {
  return useApiMutation<void, void>(
    () => ({ path: `/invoices/${id}`, options: { method: "DELETE" } }),
    [ANY_INVOICE, keys.dashboard()],
  );
}

/**
 * The three lifecycle transitions, which differ only in their path — so they
 * share one hook rather than three near-identical ones.
 *
 * <p>`reject` is the only one carrying a body: Product Scope §5.3 requires a
 * note, and the invoice returns to DRAFT with that note visible to whoever
 * drafted it.
 */
export type LifecycleAction =
  | { action: "submit" }
  | { action: "send" }
  | { action: "reject"; note: string };

export function useInvoiceLifecycle(id: number) {
  return useApiMutation<Invoice, LifecycleAction>(
    (input) => ({
      path: `/invoices/${id}/${input.action}`,
      options: {
        method: "POST",
        body: input.action === "reject" ? { note: input.note } : undefined,
      },
    }),
    [ANY_INVOICE, keys.dashboard()],
  );
}

export type PaymentInput = {
  amount: string;
  paidAt: string;
  method: PaymentMethod;
  note?: string;
};

export function useRecordPayment(id: number) {
  return useApiMutation<Payment, PaymentInput>(
    (input) => ({ path: `/invoices/${id}/payments`, options: { method: "POST", body: input } }),
    [ANY_INVOICE, keys.dashboard()],
  );
}
