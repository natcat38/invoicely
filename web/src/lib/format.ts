import type { InvoiceStatus, PaymentMethod } from "./types";

/**
 * Display formatting. Nothing in this file computes anything.
 *
 * <p>That is deliberate and worth stating once: the API is authoritative for
 * every figure on screen. It rounds each amount to two decimals with HALF_UP
 * before sending it (ADR-0006), and it sends `subtotal`, `gst`, `total`,
 * `amountPaid` and `balance` as separate fields precisely so a client never
 * has to add them up. JavaScript numbers are binary floats — `0.1 + 0.2` is
 * `0.30000000000000004` — so any total this app calculated itself would
 * eventually disagree with the invoice the client received. Format, never
 * calculate.
 */

/**
 * The same amount without the currency symbol, for a table column that has
 * already said "SGD" in its header. Keeps the grouping and both decimals.
 */
const PLAIN_AMOUNT = new Intl.NumberFormat("en-SG", {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/**
 * Singapore dollars, always with both decimals and always with the `S$`
 * prefix: `1547.8` renders as `S$1,547.80`.
 *
 * <p>Both halves of that are deliberate. Padding to two decimals keeps a money
 * column aligned — some rows ending in one decimal and some in two do not line
 * up, and an invoice reading "S$1,547.8" looks like a mistake.
 *
 * <p>The prefix is written by hand rather than taken from
 * `Intl.NumberFormat("en-SG", { currency: "SGD" })`, which renders a bare `$`:
 * to a Singaporean reader that is unambiguous, but on an invoice that may be
 * read anywhere it is not, and the Product Scope spells the currency `S$` in
 * the copy it specifies (§5.4's overpay message). One formatter, one answer,
 * matching the words the scope already chose.
 */
export function money(value: number): string {
  return `S$${PLAIN_AMOUNT.format(value)}`;
}

export function amount(value: number): string {
  return PLAIN_AMOUNT.format(value);
}

/**
 * A quantity, which unlike money is *not* padded to a fixed number of
 * decimals: "2" should read as 2, not 2.0000, while 1.5 hours must keep its
 * half. The API allows up to four decimal places.
 */
export function quantity(value: number): string {
  return new Intl.NumberFormat("en-SG", { maximumFractionDigits: 4 }).format(value);
}

/**
 * A date as a person reads it: `2026-02-01` becomes `1 Feb 2026`.
 *
 * The API sends plain `LocalDate` strings with no time and no zone, so they
 * are split by hand rather than passed to `new Date(...)`. Given "2026-02-01"
 * that constructor parses UTC midnight, which in a browser west of Greenwich
 * displays as 31 January — an invoice dated a day earlier than it says on the
 * API. The business day is Asia/Singapore anyway (ADR-0008), and a date with
 * no time in it has no business being converted between zones.
 */
export function date(isoDate: string): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  const MONTHS = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
  ];
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** Today in Singapore, as the `yyyy-mm-dd` an `<input type="date">` expects. */
export function todayInSingapore(): string {
  // `en-CA` formats as yyyy-mm-dd, which is exactly the input's wire format —
  // building it from getFullYear/getMonth would use the viewer's own zone and
  // could be a day out for someone travelling.
  return new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Singapore" }).format(new Date());
}

/** Statuses as the Product Scope writes them, not as the enum spells them. */
const STATUS_LABELS: Record<InvoiceStatus, string> = {
  DRAFT: "Draft",
  PENDING_APPROVAL: "Awaiting approval",
  SENT: "Sent",
  OVERDUE: "Overdue",
  PAID: "Paid",
};

export function statusLabel(status: InvoiceStatus): string {
  return STATUS_LABELS[status];
}

const METHOD_LABELS: Record<PaymentMethod, string> = {
  BANK_TRANSFER: "Bank transfer",
  PAYNOW: "PayNow",
  CASH: "Cash",
  CHEQUE: "Cheque",
};

export function methodLabel(method: PaymentMethod): string {
  return METHOD_LABELS[method];
}

export const PAYMENT_METHODS = Object.keys(METHOD_LABELS) as PaymentMethod[];
