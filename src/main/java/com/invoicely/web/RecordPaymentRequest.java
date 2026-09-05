package com.invoicely.web;

import com.invoicely.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The body for recording a payment against an invoice.
 *
 * <p>Notice what is absent: no invoice id (it comes from the path, and from
 * there the caller's own business — ADR-0001) and no resulting balance or
 * status. Both are derived, the same way {@link InvoiceRequest} accepts no
 * totals: the server works them out from {@link com.invoicely.domain.InvoiceTotals}
 * rather than trusting a number the client already computed.
 *
 * @param amount how much arrived; must be positive and cannot exceed the
 *               invoice's remaining balance (checked in {@link PaymentService},
 *               since that needs the invoice loaded first)
 * @param paidAt the day the money arrived, as entered by the owner
 * @param method how it arrived
 * @param note   optional free text, e.g. a reference number
 */
public record RecordPaymentRequest(
        @NotNull(message = "Enter an amount.")
        @DecimalMin(value = "0.00", inclusive = false, message = "Amount must be greater than zero.")
        @Digits(integer = 17, fraction = 2, message = "Amount allows at most 2 decimal places.")
        BigDecimal amount,

        @NotNull(message = "Enter the date the payment was received.")
        LocalDate paidAt,

        @NotNull(message = "Choose how the payment was made.")
        PaymentMethod method,

        @Size(max = 1000, message = "Keep the note under 1000 characters.")
        String note) {
}
