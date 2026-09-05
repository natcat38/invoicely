package com.invoicely.web;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Client;
import com.invoicely.domain.ClientRepository;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceNumbering;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Everything the invoice endpoints do that is not HTTP.
 *
 * <p>Two rules run through every method here:
 *
 * <ul>
 *   <li>The business id comes from {@link CurrentRequest} and is applied to
 *       every lookup, so a row belonging to someone else is simply not found
 *       (ADR-0001).
 *   <li>Only a DRAFT invoice can be changed. Anything else has been seen by a
 *       client, and Product Scope §5.3 answers an edit attempt with
 *       "Sent invoices can't be edited. Create a new invoice."
 * </ul>
 *
 * <p>Moving an invoice <em>between</em> statuses is not here — submit, send,
 * reject and payments arrive in Task 5, where the role checks they need exist.
 *
 * <p>Every method returns a response record rather than an entity, because
 * {@code spring.jpa.open-in-view} is off: once the transaction closes, an
 * entity cannot load anything it has not already loaded. Mapping while the
 * transaction is still open keeps that boundary obvious instead of turning it
 * into a lazy-loading failure in the controller.
 */
@Service
@Transactional
public class InvoiceService {

    private final InvoiceRepository invoices;
    private final ClientRepository clients;
    private final BusinessRepository businesses;
    private final UserRepository users;
    private final InvoiceNumbering numbering;
    private final CurrentRequest currentRequest;

    InvoiceService(InvoiceRepository invoices,
                   ClientRepository clients,
                   BusinessRepository businesses,
                   UserRepository users,
                   InvoiceNumbering numbering,
                   CurrentRequest currentRequest) {
        this.invoices = invoices;
        this.clients = clients;
        this.businesses = businesses;
        this.users = users;
        this.numbering = numbering;
        this.currentRequest = currentRequest;
    }

    public InvoiceResponse create(InvoiceRequest request) {
        Long businessId = currentRequest.businessId();

        // Loaded with a write lock, which is what makes the number allocation
        // below safe against a second create landing at the same moment.
        Business business = businesses.findByIdForUpdate(businessId)
                .orElseThrow(() -> new NotFoundException("Business"));
        Client client = requireClient(request.clientId(), businessId);
        User createdBy = users.findByIdAndBusinessId(currentRequest.userId(), businessId)
                .orElseThrow(() -> new NotFoundException("User"));

        LocalDate issueDate = request.issueDate() == null ? LocalDate.now() : request.issueDate();
        LocalDate dueDate = request.dueDate() == null
                ? issueDate.plusDays(business.getDefaultPaymentTermsDays())
                : request.dueDate();
        requireSensibleDates(issueDate, dueDate);

        Invoice invoice = new Invoice(business, client, createdBy,
                numbering.next(businessId, issueDate), issueDate, dueDate);
        applyLineItems(invoice, request);
        return InvoiceResponse.from(invoices.save(invoice));
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long id) {
        return InvoiceResponse.from(load(id));
    }

    /**
     * The list, in "needs attention" order. Both filters are optional.
     *
     * <p>Each row needs its client name, line items and payments to show a
     * total and a balance, which would be one query per invoice. The
     * {@code hibernate.default_batch_fetch_size} setting in
     * application.properties collapses those into a handful of batched queries
     * per page instead.
     */
    @Transactional(readOnly = true)
    public Page<InvoiceSummaryResponse> list(InvoiceStatus status, Long clientId, Pageable pageable) {
        return invoices.findForList(
                        currentRequest.businessId(), status, clientId, LocalDate.now(), pageable)
                .map(InvoiceSummaryResponse::from);
    }

    /** Replaces a draft wholesale. The number, business and author never change. */
    public InvoiceResponse update(Long id, InvoiceRequest request) {
        Invoice invoice = load(id);
        requireDraft(invoice, "Sent invoices can't be edited. Create a new invoice.");

        Long businessId = currentRequest.businessId();
        invoice.setClient(requireClient(request.clientId(), businessId));

        LocalDate issueDate = request.issueDate() == null ? invoice.getIssueDate() : request.issueDate();
        LocalDate dueDate = request.dueDate() == null ? invoice.getDueDate() : request.dueDate();
        requireSensibleDates(issueDate, dueDate);
        invoice.setIssueDate(issueDate);
        invoice.setDueDate(dueDate);

        invoice.clearLineItems();
        applyLineItems(invoice, request);
        return InvoiceResponse.from(invoice);
    }

    public void delete(Long id) {
        Invoice invoice = load(id);
        requireDraft(invoice, "Only draft invoices can be deleted. This one has already been sent.");
        invoices.delete(invoice);
    }

    /** The one ownership-scoped lookup every other method here goes through. */
    private Invoice load(Long id) {
        return invoices.findByIdAndBusinessId(id, currentRequest.businessId())
                .orElseThrow(() -> new NotFoundException("Invoice"));
    }

    private Client requireClient(Long clientId, Long businessId) {
        return clients.findByIdAndBusinessId(clientId, businessId)
                .orElseThrow(() -> new NotFoundException("Client"));
    }

    private void applyLineItems(Invoice invoice, InvoiceRequest request) {
        request.lineItems().forEach(line ->
                invoice.addLineItem(line.description(), line.quantity(), line.unitPrice()));
    }

    /**
     * Mirrors the {@code due_date >= issue_date} check in the V1 migration, so
     * the caller gets a readable 400 rather than the 500 a raw constraint
     * violation would produce. It is a 400 and not a 409 because the request is
     * malformed — no invoice had to exist for it to be wrong.
     *
     * <p>It lives here rather than in an annotation because it compares two
     * fields, which a constraint on a single field cannot do.
     */
    private void requireSensibleDates(LocalDate issueDate, LocalDate dueDate) {
        if (dueDate.isBefore(issueDate)) {
            throw new BadRequestException("due-before-issue",
                    "The due date cannot be earlier than the issue date.");
        }
    }

    /**
     * @param detail what to tell the caller, since the wording differs by
     *               action — the edit message is fixed copy from
     *               Product Scope §5.3.
     */
    private void requireDraft(Invoice invoice, String detail) {
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new ConflictException("invoice-not-draft", detail);
        }
    }
}
