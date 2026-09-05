package com.invoicely.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The four money figures an invoice shows, rounded the way they are displayed
 * and paid: two decimal places, {@link RoundingMode#HALF_UP}.
 *
 * <p>Rounding happens here and nowhere earlier. Line totals are kept unrounded
 * ({@link LineItem#lineTotal()}) so that rounding is applied once to the
 * subtotal rather than once per line, where the half-cents would accumulate
 * into a visible discrepancy.
 *
 * <p>Which GST rate applies depends on whether the invoice has been sent:
 *
 * <ul>
 *   <li><b>Sent</b> — use {@code gstRateSnapshot}, the rate copied onto the
 *       invoice at send time. The client has this document; its numbers must
 *       never move afterwards.
 *   <li><b>Not sent yet</b> — use the current business setting, so a draft
 *       reflects a rate change made this morning.
 *   <li><b>Business not GST-registered</b> — no GST line at all, which is not
 *       the same as a zero one. {@link #hasGst()} distinguishes them.
 * </ul>
 *
 * <p>See knowledge/domain/money.md and Tech Scope §2 for the worked examples
 * these rules come from.
 */
public record InvoiceTotals(
        BigDecimal subtotal,
        /** The rate that was applied, e.g. 0.0900, or null if no GST applies at all. */
        BigDecimal gstRate,
        BigDecimal gst,
        BigDecimal total,
        BigDecimal balance) {

    private static final int MONEY_SCALE = 2;

    public static InvoiceTotals of(Invoice invoice) {
        BigDecimal subtotal = roundMoney(invoice.getLineItems().stream()
                .map(LineItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal rate = applicableGstRate(invoice);
        BigDecimal gst = rate == null ? BigDecimal.ZERO : roundMoney(subtotal.multiply(rate));
        BigDecimal total = subtotal.add(gst);

        BigDecimal paid = roundMoney(invoice.getPayments().stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        return new InvoiceTotals(subtotal, rate, gst, total, total.subtract(paid));
    }

    /**
     * The rate to charge, or null when no GST applies.
     *
     * <p>Null and zero mean different things here: a business that is not
     * GST-registered shows no GST line at all, while a registered business on a
     * 0% rate shows one reading 0.00. Keeping the rate rather than only the
     * amount is also what lets the document print the "GST 9%" label.
     *
     * <p>The test is whether the invoice has been <em>sent</em>, not whether a
     * snapshot happens to be present. Those differ in exactly the case that
     * matters: a business that was not GST-registered when it sent an invoice
     * stored no snapshot, and if it registers later, falling back to the live
     * setting would grow a GST line on a document the client already has. Once
     * sent, the snapshot is the whole answer — including when it is null.
     */
    private static BigDecimal applicableGstRate(Invoice invoice) {
        if (invoice.hasBeenSent()) {
            return invoice.getGstRateSnapshot();
        }
        Business business = invoice.getBusiness();
        return business.isGstRegistered() ? business.getGstRate() : null;
    }

    /**
     * The rounding rule for every amount this API reports, in one place so that
     * a line total and the subtotal it feeds can never round differently.
     */
    public static BigDecimal roundMoney(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Whether the invoice shows a GST line at all. */
    public boolean hasGst() {
        return gstRate != null;
    }
}
