package com.invoicely.web;

import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import com.invoicely.domain.Payment;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording money against an invoice, and listing what has been recorded.
 *
 * <p>Owner-only throughout — Product Scope §3 keeps money away from staff
 * entirely, so unlike {@link InvoiceLifecycleService} there is no method here
 * that a staff member may reach at all. The role check still lives on
 * {@link PaymentController} as {@code @PreAuthorize}, not here, for the same
 * reason as everywhere else in this codebase: it is the role axis (403), kept
 * apart from the state axis (409) and the validation axis (400) this class
 * enforces.
 */
@Service
@Transactional
public class PaymentService {

    private final InvoiceRepository invoices;
    private final UserRepository users;
    private final CurrentRequest currentRequest;

    PaymentService(InvoiceRepository invoices, UserRepository users, CurrentRequest currentRequest) {
        this.invoices = invoices;
        this.users = users;
        this.currentRequest = currentRequest;
    }

    /**
     * Records a payment, in the order Product Scope §5.4 implies:
     *
     * <ol>
     *   <li>the invoice must belong to the caller's business (404 otherwise —
     *       {@link #load});
     *   <li>it must have been sent — a draft has not been issued, so there is
     *       nothing to have been paid (409);
     *   <li>it must not already be PAID — recording anything against it would
     *       overpay, but that is a state problem, not a validation one (409);
     *   <li>the amount must not exceed what is still owed (400, with the exact
     *       balance in the message so the owner can correct it);
     *   <li>a payment that brings the balance to exactly zero flips the
     *       invoice to PAID.
     * </ol>
     */
    public PaymentResponse record(Long invoiceId, RecordPaymentRequest request) {
        Invoice invoice = load(invoiceId);

        if (!invoice.hasBeenSent()) {
            throw new ConflictException("invoice-not-sent",
                    "This invoice has not been sent, so there is nothing to have been paid.");
        }
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new ConflictException("invoice-already-paid", "This invoice is already fully paid.");
        }

        BigDecimal balance = InvoiceTotals.of(invoice).balance();
        if (request.amount().compareTo(balance) > 0) {
            throw new BadRequestException("payment-exceeds-balance",
                    "Amount exceeds the remaining balance (" + formatMoney(balance) + ").");
        }

        Payment payment = invoice.addPayment(request.amount(), request.paidAt(), request.method(), currentUser());
        payment.setNote(request.note());

        // Recomputed after the payment is attached, rather than compared to
        // the balance above, so this is always the honest question — "is the
        // invoice paid off now?" — and not an assumption that this payment
        // was the one that finished it.
        BigDecimal balanceAfter = InvoiceTotals.of(invoice).balance();
        if (balanceAfter.compareTo(BigDecimal.ZERO) == 0
                && invoice.getStatus().canTransitionTo(InvoiceStatus.PAID)) {
            invoice.setStatus(InvoiceStatus.PAID);
        }

        // Flushed before mapping, because the response and the Location header
        // both need the generated id. Nothing above this line runs a query, so
        // Hibernate has had no reason to flush on its own, and the id would
        // still be null until the transaction committed.
        //
        // flush(), not saveAndFlush(invoice): the invoice is already managed,
        // so saving it would merge it — and merge is not cascaded to the
        // payments collection, which only cascades PERSIST. The new payment
        // would be inserted without its invoice_id.
        invoices.flush();
        return PaymentResponse.from(payment);
    }

    /** The payments on one invoice, newest first — Payment itself orders oldest first for the balance math. */
    @Transactional(readOnly = true)
    public List<PaymentResponse> list(Long invoiceId) {
        Invoice invoice = load(invoiceId);
        return invoice.getPayments().stream()
                .sorted(Comparator.comparing(Payment::getPaidAt).thenComparing(Payment::getId).reversed())
                .map(PaymentResponse::from)
                .toList();
    }

    private User currentUser() {
        return users.findByIdAndBusinessId(currentRequest.userId(), currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("User"));
    }

    /** The one ownership-scoped lookup every method here goes through — see ADR-0001. */
    private Invoice load(Long invoiceId) {
        return invoices.findByIdAndBusinessId(invoiceId, currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Invoice"));
    }

    /** {@code S$1,047.80} — the exact format Product Scope §5.4 shows in the overpay message. */
    private String formatMoney(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "S$" + format.format(amount);
    }
}
