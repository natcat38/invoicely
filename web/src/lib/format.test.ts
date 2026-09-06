import { describe, expect, it } from "vitest";
import { amount, date, dateOfInstant, money, quantity } from "./format";

/**
 * The formatters that have a way of being subtly wrong.
 *
 * <p>Two of these exist because of bugs found in review rather than in
 * theory: `money` used to print a bare `$`, and `dateOfInstant` exists
 * because a timestamp was once passed to `date` and rendered as
 * "NaN Sep 2026".
 */
describe("money", () => {
  it("prefixes S$, so the currency is not ambiguous outside Singapore", () => {
    // Intl with en-SG gives a bare "$", and the Product Scope's copy says S$.
    expect(money(1547.8)).toBe("S$1,547.80");
  });

  it("always shows both decimals, so a money column lines up", () => {
    expect(money(0)).toBe("S$0.00");
    expect(money(1420)).toBe("S$1,420.00");
  });
});

describe("amount", () => {
  it("drops the symbol but keeps grouping and both decimals", () => {
    expect(amount(1547.8)).toBe("1,547.80");
  });
});

describe("quantity", () => {
  it("does not pad, because a quantity of 2 is not 2.0000", () => {
    expect(quantity(2)).toBe("2");
    expect(quantity(1.5)).toBe("1.5");
  });
});

describe("date", () => {
  it("reads a LocalDate literally, with no timezone conversion", () => {
    // The bug this guards: `new Date("2026-02-01")` is UTC midnight, which
    // prints as 31 January anywhere west of Greenwich. The invoice would show
    // a date one day earlier than the one the API stored.
    expect(date("2026-02-01")).toBe("1 Feb 2026");
    expect(date("2026-12-31")).toBe("31 Dec 2026");
  });
});

describe("dateOfInstant", () => {
  it("converts a real timestamp to the Singapore business day", () => {
    // 20:00 UTC is already the next morning in Singapore (UTC+8), and the
    // business day is what this product measures in (ADR-0008).
    expect(dateOfInstant("2026-09-05T20:00:00Z")).toBe("6 Sep 2026");
    expect(dateOfInstant("2026-09-05T10:00:00Z")).toBe("5 Sep 2026");
  });

  it("is not interchangeable with date()", () => {
    // Passing a timestamp to `date` is the mistake that produced "NaN Sep
    // 2026" on the Team page. Asserted so the two stay distinct rather than
    // being "simplified" into one function later.
    expect(date("2026-09-05T20:00:00Z")).toContain("NaN");
    expect(dateOfInstant("2026-09-05T20:00:00Z")).not.toContain("NaN");
  });
});
