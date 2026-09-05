package com.invoicely.web;

import com.invoicely.domain.Business;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving an invoice between states: submit, send, reject.
 *
 * <p>Separate from {@link InvoiceService}, which owns the invoice's
 * <em>content</em>. Content may only change while an invoice is a draft; state
 * changes are the whole point once it stops being one, and they are the part
 * with the maker-checker rules attached.
 *
 * <p>Two independent checks guard every method:
 *
 * <ul>
 *   <li><b>Role</b>, as {@code @PreAuthorize} on {@link InvoiceController} —
 *       403. It runs before the method body, so a staff member trying to send
 *       is told about their role, not about the invoice.
 *   <li><b>State</b>, as {@link InvoiceStatus#canTransitionTo} — 409, naming
 *       the status the invoice is actually in, which is what the person on the
 *       other end needs to know.
 * </ul>
 */
@Service
@Transactional
public class InvoiceLifecycleService {

    private final InvoiceRepository invoices;
    private final UserRepository users;
    private final CurrentRequest currentRequest;

    InvoiceLifecycleService(InvoiceRepository invoices, UserRepository users,
                            CurrentRequest currentRequest) {
        this.invoices = invoices;
        this.users = users;
        this.currentRequest = currentRequest;
    }

    /**
     * Staff hands a draft to the owner for approval. Any role may do this — an
     * owner submitting their own draft is allowed, even though they could send
     * it directly, because a small business may still want the queue.
     */
    public InvoiceResponse submit(Long id) {
        Invoice invoice = load(id);
        requireTransition(invoice, InvoiceStatus.PENDING_APPROVAL);
        // A resubmission after a rejection should not still be showing the old
        // rejection note next to it.
        invoice.setRejectionNote(null);
        invoice.setStatus(InvoiceStatus.PENDING_APPROVAL);
        return InvoiceResponse.from(invoice);
    }

    /**
     * The owner issues the invoice to the client — from DRAFT directly, or from
     * PENDING_APPROVAL as the "approve &amp; send" half of the queue.
     *
     * <p>This is the moment the GST rate is frozen. Everything the client will
     * ever be shown about this invoice is fixed here, so that changing the
     * business GST setting tomorrow cannot rewrite a document somebody has
     * already received.
     */
    public InvoiceResponse send(Long id) {
        Invoice invoice = load(id);
        requireTransition(invoice, InvoiceStatus.SENT);
        if (invoice.getLineItems().isEmpty()) {
            throw new ConflictException("invoice-has-no-lines",
                    "Add at least one line before sending this invoice.");
        }

        Business business = invoice.getBusiness();
        // Null for a business that is not GST-registered, which is not the same
        // as zero: it means this invoice shows no GST line at all, for good.
        invoice.setGstRateSnapshot(business.isGstRegistered() ? business.getGstRate() : null);
        invoice.setSentAt(Instant.now());
        invoice.setSentBy(currentUser());
        invoice.setRejectionNote(null);
        invoice.setStatus(InvoiceStatus.SENT);
        return InvoiceResponse.from(invoice);
    }

    /**
     * The owner sends a submitted invoice back to the staff member who built
     * it. The note is required: "rejected" on its own tells them nothing about
     * what to fix.
     */
    public InvoiceResponse reject(Long id, String note) {
        Invoice invoice = load(id);
        requireTransition(invoice, InvoiceStatus.DRAFT);
        invoice.setRejectionNote(note);
        invoice.setStatus(InvoiceStatus.DRAFT);
        return InvoiceResponse.from(invoice);
    }

    private void requireTransition(Invoice invoice, InvoiceStatus target) {
        if (!invoice.getStatus().canTransitionTo(target)) {
            throw new ConflictException("illegal-status-transition",
                    "This invoice is " + readable(invoice.getStatus()) + ", so it cannot be "
                            + readable(target) + ".");
        }
    }

    /** "pending approval" rather than "PENDING_APPROVAL" — this reaches a person. */
    private String readable(InvoiceStatus status) {
        return status.name().toLowerCase().replace('_', ' ');
    }

    private User currentUser() {
        return users.findByIdAndBusinessId(currentRequest.userId(), currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("User"));
    }

    private Invoice load(Long id) {
        return invoices.findByIdAndBusinessId(id, currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Invoice"));
    }
}
