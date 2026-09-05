package com.invoicely.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * The business settings an owner may change (Product Scope §5.2a).
 *
 * <p>{@code gstRate} is a fraction, so 9% is {@code 0.09}. It is kept even when
 * {@code gstRegistered} is false, so switching registration back on does not
 * lose it.
 *
 * <p>Changing the rate never touches an invoice that has already been sent:
 * those carry their own snapshot, taken at send time. See
 * {@link com.invoicely.domain.InvoiceTotals}.
 *
 * <p>{@code address} and {@code uen} are the invoice document's letterhead
 * (ADR-0011) and, unlike every other field here, are optional: a business may
 * legitimately have neither set, and the document just prints a thinner
 * letterhead rather than rejecting the request.
 */
public record SettingsRequest(
        @NotBlank(message = "Enter a business name.")
        @Size(max = 255, message = "Keep the business name under 255 characters.")
        String name,

        @NotNull(message = "Say whether the business is GST-registered.")
        Boolean gstRegistered,

        @NotNull(message = "Enter a GST rate.")
        @DecimalMin(value = "0.0000", message = "The GST rate cannot be negative.")
        @DecimalMax(value = "0.9999", message = "The GST rate must be below 100%.")
        @Digits(integer = 1, fraction = 4, message = "The GST rate allows at most 4 decimal places.")
        BigDecimal gstRate,

        @NotNull(message = "Choose payment terms.")
        Integer defaultPaymentTermsDays,

        @Size(max = 500, message = "Keep the address under 500 characters.")
        String address,

        @Size(max = 20, message = "Keep the UEN under 20 characters.")
        String uen) {
}
