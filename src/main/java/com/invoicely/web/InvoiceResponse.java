package com.invoicely.web;

import com.invoicely.domain.BusinessCalendar;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import com.invoicely.domain.LineItem;
import com.invoicely.domain.OverdueInvoices;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A whole invoice, as the builder screen and the printed document need it.
 *
 * <p>All money arrives already rounded to two decimals by
 * {@link InvoiceTotals} — a client should never have to decide how to round.
 * {@code gstRate} is null when the business does not charge GST, which is how
 * the document knows to omit the GST line entirely rather than print 0.00.
 *
 * <p>The status is computed rather than read straight off the row. The daily
 * job that persists SENT to OVERDUE runs once a night, so between midnight and
 * the job an invoice can be overdue in reality while still stored as SENT.
 * {@link OverdueInvoices#asOf} reports what is true now; the job makes it true
 * in the database, so that queries and the dashboard can still filter on it.
 *
 * <p>{@code client} and {@code business} are read live off today's
 * {@code clients}/{@code businesses} rows on every call — a correction to an
 * address or UEN applies to every invoice at once, past and future.
 * {@code gstRate} is the one exception: once an invoice is sent it is frozen
 * in {@code gst_rate_snapshot}, because it determines the amount owed rather
 * than merely how the document is presented. See
 * {@code docs/adr/0011-invoice-document-data-contract.md} for why the line is
 * drawn there and not anywhere else on this record.
 */
public record InvoiceResponse(
        Long id,
        String number,
        InvoiceStatus status,
        ClientSummary client,
        BusinessSummary business,
        LocalDate issueDate,
        LocalDate dueDate,
        List<LineItemResponse> lineItems,
        BigDecimal subtotal,
        BigDecimal gstRate,
        BigDecimal gst,
        BigDecimal total,
        BigDecimal amountPaid,
        BigDecimal balance,
        String rejectionNote,
        Instant sentAt,
        Instant createdAt) {

    public static InvoiceResponse from(Invoice invoice) {
        InvoiceTotals totals = InvoiceTotals.of(invoice);
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getNumber(),
                OverdueInvoices.asOf(invoice, BusinessCalendar.today()),
                ClientSummary.from(invoice),
                BusinessSummary.from(invoice),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList(),
                totals.subtotal(),
                totals.gstRate(),
                totals.gst(),
                totals.total(),
                // InvoiceTotals computes this internally as the sum of payments,
                // then folds it straight into balance. Rather than have this
                // DTO re-sum invoice.getPayments() itself and risk the two
                // figures disagreeing, it is recovered from the two totals that
                // already carry it: total - balance == amountPaid exactly,
                // because both were rounded by InvoiceTotals to the same scale.
                totals.total().subtract(totals.balance()),
                totals.balance(),
                invoice.getRejectionNote(),
                invoice.getSentAt(),
                invoice.getCreatedAt());
    }

    /**
     * Everything the bill-to block prints. Not just enough to identify the
     * client any more (ADR-0011 added {@code address}, {@code contactPerson}
     * and {@code email}) — the full client record is still a separate
     * request, so a change of address does not have to ripple through every
     * other invoice response shape that only needs the name.
     */
    public record ClientSummary(
            Long id, String name, String address, String contactPerson, String email, String uen, String paymentNotes) {

        static ClientSummary from(Invoice invoice) {
            var client = invoice.getClient();
            return new ClientSummary(
                    client.getId(),
                    client.getName(),
                    client.getAddress(),
                    client.getContactPerson(),
                    client.getEmail(),
                    client.getUen(),
                    client.getPaymentNotes());
        }
    }

    /**
     * The document's "from" block — the business's own letterhead. Read live
     * off {@code businesses} rather than snapshotted; see the class Javadoc.
     *
     * <p>{@code gstRegistered} is here so the document knows whether to print
     * a "GST Reg. No." line at all — a business that is not registered must
     * omit that line, not print it with an empty UEN, the same way the GST
     * amount line itself is omitted rather than shown as 0.00.
     */
    public record BusinessSummary(String name, String address, String uen, boolean gstRegistered) {

        static BusinessSummary from(Invoice invoice) {
            var business = invoice.getBusiness();
            return new BusinessSummary(
                    business.getName(), business.getAddress(), business.getUen(), business.isGstRegistered());
        }
    }

    /**
     * A line, with its own total precomputed. The client could multiply the two
     * itself, but then the rounding rule would live in two places and they
     * would eventually disagree.
     */
    public record LineItemResponse(
            Long id, String description, BigDecimal quantity, BigDecimal unitPrice, BigDecimal lineTotal) {

        static LineItemResponse from(LineItem item) {
            return new LineItemResponse(
                    item.getId(),
                    item.getDescription(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    InvoiceTotals.roundMoney(item.lineTotal()));
        }
    }
}
