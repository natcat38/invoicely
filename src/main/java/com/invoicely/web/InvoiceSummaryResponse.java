package com.invoicely.web;

import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the invoice list: exactly the columns Product Scope §5.3 names —
 * number, status, client, total, balance due, due date — and nothing else.
 *
 * <p>Line items are left out on purpose. A list of fifty invoices does not need
 * five hundred line items to render six columns, and sending them would make
 * the list slower the more work the business does.
 */
public record InvoiceSummaryResponse(
        Long id,
        String number,
        InvoiceStatus status,
        Long clientId,
        String clientName,
        LocalDate issueDate,
        LocalDate dueDate,
        BigDecimal total,
        BigDecimal balance) {

    public static InvoiceSummaryResponse from(Invoice invoice) {
        InvoiceTotals totals = InvoiceTotals.of(invoice);
        return new InvoiceSummaryResponse(
                invoice.getId(),
                invoice.getNumber(),
                invoice.getStatus(),
                invoice.getClient().getId(),
                invoice.getClient().getName(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                totals.total(),
                totals.balance());
    }
}
