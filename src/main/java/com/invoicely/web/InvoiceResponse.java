package com.invoicely.web;

import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import com.invoicely.domain.LineItem;
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
 */
public record InvoiceResponse(
        Long id,
        String number,
        InvoiceStatus status,
        ClientSummary client,
        LocalDate issueDate,
        LocalDate dueDate,
        List<LineItemResponse> lineItems,
        BigDecimal subtotal,
        BigDecimal gstRate,
        BigDecimal gst,
        BigDecimal total,
        BigDecimal balance,
        String rejectionNote,
        Instant sentAt,
        Instant createdAt) {

    public static InvoiceResponse from(Invoice invoice) {
        InvoiceTotals totals = InvoiceTotals.of(invoice);
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getNumber(),
                invoice.getStatus(),
                ClientSummary.from(invoice),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList(),
                totals.subtotal(),
                totals.gstRate(),
                totals.gst(),
                totals.total(),
                totals.balance(),
                invoice.getRejectionNote(),
                invoice.getSentAt(),
                invoice.getCreatedAt());
    }

    /**
     * Just enough of the client to render the invoice. The full client record
     * is a separate request, so a change of address does not have to ripple
     * through every invoice response shape.
     */
    public record ClientSummary(Long id, String name, String uen, String paymentNotes) {

        static ClientSummary from(Invoice invoice) {
            var client = invoice.getClient();
            return new ClientSummary(
                    client.getId(), client.getName(), client.getUen(), client.getPaymentNotes());
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
                    item.lineTotal().setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }
}
