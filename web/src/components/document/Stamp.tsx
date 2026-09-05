import type { InvoiceStatus } from "@/lib/types";

/**
 * The rubber stamp on the document: red OVERDUE, green PAID.
 *
 * <p>Only those two states get one. DRAFT, awaiting-approval and SENT are
 * quiet outline badges in the app chrome and appear nowhere on the paper —
 * the Design Direction is explicit that a stamp is for news, and "this
 * invoice is proceeding normally" is not news.
 *
 * <p>Rotated -3° and letterspaced, so it reads as something pressed onto the
 * sheet after it was printed rather than as part of the layout. It is
 * positioned by its container — the totals row — rather than choosing a
 * corner of the page for itself, so that it sits beside the balance it is
 * commenting on and never lands on top of the invoice number.
 */
export function Stamp({ status }: { status: InvoiceStatus }) {
  if (status !== "OVERDUE" && status !== "PAID") return null;

  const isPaid = status === "PAID";

  return (
    <div
      // Decorative: the status is already announced in the app chrome's badge
      // and in this document's aria-label, so repeating it here would make a
      // screen reader say it three times. Hidden rather than duplicated.
      aria-hidden="true"
      className={[
        "pointer-events-none absolute left-0 top-2 select-none rounded-md border-4 px-4 py-1.5",
        "font-mono text-xl font-bold uppercase tracking-[0.25em] opacity-80",
        // The press: scales down from 1.15 as it appears, easing out, like a
        // stamp meeting paper. The Design Direction allows exactly one motion
        // moment in the product and this is it. `prefers-reduced-motion` is
        // honoured globally in index.css, which collapses the duration to
        // nothing rather than needing a second class here.
        "motion-safe:animate-[stamp-press_220ms_ease-out]",
        isPaid ? "border-stamp-paid text-stamp-paid" : "border-stamp-overdue text-stamp-overdue",
      ].join(" ")}
      style={{ transform: "rotate(-3deg)" }}
    >
      {isPaid ? "Paid" : "Overdue"}
    </div>
  );
}
