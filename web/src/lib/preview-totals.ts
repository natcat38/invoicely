/**
 * The only arithmetic in this app, and it exists for one screen.
 *
 * <p>The invoice builder shows a live document preview that updates as line
 * items are typed, before anything is saved — so there is no server answer to
 * display yet. Everywhere else, totals come from the API and are only
 * formatted (see `lib/format.ts`).
 *
 * <p>**The API remains authoritative.** The moment a draft is saved, the
 * server's figures replace these. This exists to make the preview honest
 * while typing, not to be a second source of truth — so it mirrors
 * `InvoiceTotals.of` exactly, including the two things that are easy to get
 * subtly wrong:
 *
 * <ul>
 *   <li>line totals are summed <em>unrounded</em>, and the subtotal is rounded
 *       once at the end — rounding each line first would drift by a cent on
 *       invoices with fractional quantities;
 *   <li>GST is rounded separately from the subtotal it is computed on, and
 *       the total is their sum — not a rounding of subtotal × (1 + rate).
 * </ul>
 *
 * <p>All of it is done in integers (BigInt, so a large invoice cannot
 * outgrow a float's 53 bits of precision). JavaScript numbers are binary floats:
 * `0.1 * 3` is `0.30000000000000004`, and `Math.round` on a value that is a
 * hair below `.5` rounds the wrong way. Scaling to integers first means the
 * HALF_UP rule (ADR-0006) lands on exactly the same cent the server picks.
 */

/**
 * Quantities and unit prices both carry up to 4 decimals, matching the API's
 * `@Digits(fraction = 4)` on each (InvoiceRequest.java) and the numeric(19,4)
 * columns. Rounding prices to 2 decimals here would show 1.30 for 10 x 0.125
 * while the server says 1.25.
 */
const QUANTITY_SCALE = 10_000n;
const PRICE_SCALE = 10_000n;
/** Money leaves this module in cents. */
const CENTS_PER_DOLLAR = 100;
/**
 * A line total lands in units of 1e-8 dollars: quantity's 1e-4 times price's
 * 1e-4. This divisor takes that to cents (1e-8 / 1e-2 = 1e-6).
 */
const UNITS_PER_CENT = (QUANTITY_SCALE * PRICE_SCALE) / 100n;
/** GST rates are stored with 4 decimals, e.g. 0.0900. */
const RATE_SCALE = 10_000n;

export type PreviewLine = { quantity: string; unitPrice: string };

export type PreviewTotals = {
  subtotal: number;
  /** Null when the business charges no GST — the document omits the line entirely. */
  gstRate: number | null;
  gst: number;
  total: number;
};

/**
 * Divides two non-negative integers, rounding halves up.
 *
 * <p>`Math.round(n / d)` would be wrong twice over: the division itself can
 * land a hair below a .5 boundary, and `Math.round` is round-half-*up*
 * towards positive infinity rather than away from zero. Staying in integers
 * sidesteps both. Amounts here are never negative — quantities and prices are
 * validated as positive — so this only has to be right for that case, which
 * the assertion documents rather than assumes silently.
 */
function divideHalfUp(numerator: bigint, denominator: bigint): bigint {
  if (numerator < 0n) throw new Error("divideHalfUp expects non-negative amounts");
  return (2n * numerator + denominator) / (2n * denominator);
}

/**
 * Parses a form field into a scaled integer, treating anything unparseable as
 * zero — a half-typed "12." must render as a preview, not as NaN across the
 * whole document.
 */
function scaled(value: string, scale: bigint): bigint {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) return 0n;
  return BigInt(Math.round(parsed * Number(scale)));
}

function lineUnits(line: PreviewLine): bigint {
  return scaled(line.quantity, QUANTITY_SCALE) * scaled(line.unitPrice, PRICE_SCALE);
}

function dollars(cents: bigint): number {
  return Number(cents) / CENTS_PER_DOLLAR;
}

/** One line's total, rounded for display. The subtotal does not use this. */
export function previewLineTotal(line: PreviewLine): number {
  return dollars(divideHalfUp(lineUnits(line), UNITS_PER_CENT));
}

/**
 * @param gstRate the business's current rate, or null when it is not
 *                GST-registered. A draft always uses the live setting: the
 *                snapshot is only written when the invoice is sent, and by
 *                then this preview is no longer in use.
 */
export function previewTotals(lines: PreviewLine[], gstRate: number | null): PreviewTotals {
  // Summed unrounded, exactly as InvoiceTotals.of does, then rounded once.
  const subtotalUnits = lines.reduce((sum, line) => sum + lineUnits(line), 0n);
  const subtotalCents = divideHalfUp(subtotalUnits, UNITS_PER_CENT);

  const gstCents =
    gstRate === null ? 0n : divideHalfUp(subtotalCents * scaled(String(gstRate), RATE_SCALE), RATE_SCALE);

  return {
    subtotal: dollars(subtotalCents),
    gstRate,
    gst: dollars(gstCents),
    total: dollars(subtotalCents + gstCents),
  };
}
