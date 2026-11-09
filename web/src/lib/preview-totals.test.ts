import { describe, expect, it } from "vitest";
import { previewLineTotal, previewTotals } from "./preview-totals";

/**
 * The preview's arithmetic against the API's.
 *
 * <p>These are the same cases as the Java `InvoiceTotalsTest`, on purpose: the
 * preview's whole job is to show what the server will say, so the test that
 * matters is whether the two agree. If the server's rounding ever changes,
 * both suites should fail together rather than the UI quietly drifting a cent
 * away from the invoice the client receives.
 */
describe("previewTotals", () => {
  it("matches the Tech Scope's worked example for a GST-registered business", () => {
    // 2 x 400.00 + 1 x 620.00 = 1,420.00, GST 9% = 127.80, total 1,547.80.
    const totals = previewTotals(
      [
        { quantity: "2", unitPrice: "400.00" },
        { quantity: "1", unitPrice: "620.00" },
      ],
      0.09,
    );

    expect(totals.subtotal).toBe(1420);
    expect(totals.gst).toBe(127.8);
    expect(totals.total).toBe(1547.8);
  });

  it("omits GST entirely for a business that is not registered", () => {
    const totals = previewTotals([{ quantity: "2", unitPrice: "710.00" }], null);

    expect(totals.subtotal).toBe(1420);
    expect(totals.gstRate).toBeNull();
    expect(totals.gst).toBe(0);
    expect(totals.total).toBe(1420);
  });

  it("rounds a GST half-cent up, not to even", () => {
    // 100.05 x 0.09 = 9.0045 -> 9.00; 55.00 x 0.09 = 4.95 exactly.
    expect(previewTotals([{ quantity: "1", unitPrice: "100.05" }], 0.09).gst).toBe(9);
    // 0.50 x 0.09 = 0.045, which HALF_UP takes to 0.05 (round-half-to-even
    // would give 0.04, and that is the bug this case exists to catch).
    expect(previewTotals([{ quantity: "1", unitPrice: "0.50" }], 0.09).gst).toBe(0.05);
  });

  it("sums line totals unrounded, then rounds the subtotal once", () => {
    // Three lines of 0.005 each: rounding per line would give 0.01 x 3 = 0.03,
    // but the API sums first (0.015) and rounds once, to 0.02. This is the
    // exact divergence the module's comment warns about.
    const totals = previewTotals(
      [
        { quantity: "0.5", unitPrice: "0.01" },
        { quantity: "0.5", unitPrice: "0.01" },
        { quantity: "0.5", unitPrice: "0.01" },
      ],
      null,
    );

    expect(totals.subtotal).toBe(0.02);
  });

  it("keeps a unit price's third and fourth decimals, as the API does", () => {
    // The API stores prices to 4 decimals. 10 x 0.125 is exactly 1.25 on the
    // server; rounding the price to 0.13 first would show 1.30.
    expect(previewTotals([{ quantity: "10", unitPrice: "0.125" }], null).subtotal).toBe(1.25);
    // 3 x 0.3333 = 0.9999, rounded once to 1.00.
    expect(previewTotals([{ quantity: "3", unitPrice: "0.3333" }], null).subtotal).toBe(1);
    expect(previewLineTotal({ quantity: "1", unitPrice: "0.0050" })).toBe(0.01);
  });

  it("stays exact on a very large invoice", () => {
    // 1,000,000 x 123,456,789.1234 needs more than a float's 53 bits once
    // scaled to integers; BigInt keeps every digit.
    expect(previewTotals([{ quantity: "1000000", unitPrice: "123456789.1234" }], null).subtotal).toBe(
      123456789123400,
    );
  });

  it("survives a half-typed form without producing NaN", () => {
    const totals = previewTotals(
      [
        { quantity: "", unitPrice: "" },
        { quantity: "2.", unitPrice: "abc" },
        { quantity: "3", unitPrice: "10.00" },
      ],
      0.09,
    );

    expect(totals.subtotal).toBe(30);
    expect(Number.isNaN(totals.total)).toBe(false);
  });

  it("keeps fractional quantities exact where floats would drift", () => {
    // 0.1 x 3 is 0.30000000000000004 in binary floating point.
    expect(previewLineTotal({ quantity: "3", unitPrice: "0.10" })).toBe(0.3);
    // 1.15 x 2 rounds up to 2.30 under HALF_UP.
    expect(previewLineTotal({ quantity: "2", unitPrice: "1.15" })).toBe(2.3);
  });
});
