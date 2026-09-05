package com.invoicely.web;

import com.invoicely.domain.InvoiceTotals;
import com.invoicely.domain.Payment;
import com.invoicely.domain.PaymentMethod;
import com.invoicely.domain.User;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A payment as the API shows it.
 *
 * <p>{@code recordedBy} is just enough of the user to attribute the payment —
 * the same shallow-reference shape {@link InvoiceResponse.ClientSummary} uses
 * for the client, so a change to that user's own record never has to ripple
 * through every payment response.
 */
public record PaymentResponse(
        Long id,
        BigDecimal amount,
        LocalDate paidAt,
        PaymentMethod method,
        String note,
        RecordedBy recordedBy) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                // Stored at scale 4 (NUMERIC(19,4)); rounded to the 2dp the
                // API always shows money at, the same rule InvoiceTotals uses.
                InvoiceTotals.roundMoney(payment.getAmount()),
                payment.getPaidAt(),
                payment.getMethod(),
                payment.getNote(),
                RecordedBy.from(payment.getRecordedBy()));
    }

    public record RecordedBy(Long id, String name) {
        static RecordedBy from(User user) {
            return new RecordedBy(user.getId(), user.getName());
        }
    }
}
