package com.invoicely.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The body for creating or replacing a draft invoice.
 *
 * <p>Notice what is absent: no business id (it comes from the caller, ADR-0001),
 * no invoice number (assigned once at creation and never changed), no status
 * (only the Task 5 lifecycle endpoints move an invoice between states), and no
 * totals — every money figure is derived from the line items, so accepting one
 * would be accepting a number the server is about to recompute anyway.
 *
 * @param clientId  who is being billed; must belong to the same business
 * @param issueDate the invoice date; defaults to today when omitted
 * @param dueDate   when payment is due; defaults to the issue date plus the
 *                  payment terms of the business
 */
public record InvoiceRequest(
        @NotNull(message = "Choose a client.")
        Long clientId,

        LocalDate issueDate,

        LocalDate dueDate,

        @NotEmpty(message = "An invoice needs at least one line.")
        @Size(max = 200, message = "An invoice can hold at most 200 lines.")
        @Valid
        List<LineItemRequest> lineItems) {

    /**
     * One billable line.
     *
     * <p>The bounds are deliberately generous rather than absent: they exist to
     * stop a typo or a hostile request storing something the money arithmetic
     * cannot represent, not to express a business rule.
     */
    public record LineItemRequest(
            @NotBlank(message = "Describe what is being billed.")
            @Size(max = 500, message = "Keep the description under 500 characters.")
            String description,

            @NotNull(message = "Enter a quantity.")
            @DecimalMin(value = "0.0001", message = "Quantity must be greater than zero.")
            @Digits(integer = 15, fraction = 4, message = "Quantity allows at most 4 decimal places.")
            BigDecimal quantity,

            @NotNull(message = "Enter a unit price.")
            @DecimalMin(value = "0.00", message = "Unit price cannot be negative.")
            @DecimalMax(value = "99999999999.9999", message = "Unit price is too large.")
            @Digits(integer = 15, fraction = 4, message = "Unit price allows at most 4 decimal places.")
            BigDecimal unitPrice) {
    }
}
