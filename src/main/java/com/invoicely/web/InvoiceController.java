package com.invoicely.web;

import com.invoicely.domain.InvoiceStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The invoice endpoints. This class does HTTP and nothing else — read the
 * request, hand it to {@link InvoiceService}, shape the response — so the rules
 * stay in one place and stay testable without a servlet.
 *
 * <p>The lifecycle endpoints below carry the role half of the rules, as
 * {@code @PreAuthorize}. The state half lives in
 * {@link com.invoicely.domain.InvoiceStatus}. Keeping them apart is what makes
 * "staff may not send" (403) and "this invoice was already sent" (409) two
 * separate answers rather than one muddled one.
 */
@RestController
@RequestMapping("/invoices")
@Tag(name = "Invoices", description = "Invoice CRUD and the draft -> sent -> paid lifecycle.")
class InvoiceController {

    /** Big enough that the first page fills a screen, small enough to stay quick. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final InvoiceService invoiceService;
    private final InvoiceLifecycleService lifecycleService;

    InvoiceController(InvoiceService invoiceService, InvoiceLifecycleService lifecycleService) {
        this.invoiceService = invoiceService;
        this.lifecycleService = lifecycleService;
    }

    @Operation(summary = "Create a draft invoice", description = "issueDate/dueDate default from the business's "
            + "own defaults when omitted.")
    @ApiResponse(responseCode = "201", description = "Invoice created, as DRAFT.")
    @ApiResponse(responseCode = "400", description = "Validation failed, or dueDate is before issueDate.")
    @ApiResponse(responseCode = "404", description = "The client does not exist, or belongs to another business.")
    @PostMapping
    ResponseEntity<InvoiceResponse> create(@Valid @RequestBody InvoiceRequest request) {
        InvoiceResponse created = invoiceService.create(request);
        return ResponseEntity.created(URI.create("/invoices/" + created.id())).body(created);
    }

    /**
     * The invoice list, always in "needs attention" order: overdue first, then
     * waiting for approval, then newest.
     *
     * @param status   optional filter, backing the status tabs
     * @param clientId optional filter, for "everything I have billed this client"
     */
    @Operation(summary = "List invoices", description = "Always in \"needs attention\" order: overdue first, then "
            + "pending approval, then newest.")
    @GetMapping
    Page<InvoiceSummaryResponse> list(
            @Parameter(description = "Optional status filter, backing the status tabs.")
            @RequestParam(required = false) InvoiceStatus status,
            @Parameter(description = "Optional filter for everything billed to one client.")
            @RequestParam(required = false) Long clientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        // Clamped rather than rejected: a caller asking for 10,000 rows almost
        // certainly wants "lots", and a 400 would help nobody.
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return invoiceService.list(status, clientId, pageRequest);
    }

    @Operation(summary = "Get an invoice")
    @ApiResponse(responseCode = "200", description = "Invoice found.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @GetMapping("/{id}")
    InvoiceResponse get(@PathVariable Long id) {
        return invoiceService.get(id);
    }

    @Operation(summary = "Replace a draft invoice wholesale",
            description = "The number, business and author never change. Only DRAFT invoices can be edited.")
    @ApiResponse(responseCode = "200", description = "Invoice updated.")
    @ApiResponse(responseCode = "400", description = "Validation failed, or dueDate is before issueDate.")
    @ApiResponse(responseCode = "404", description = "No such invoice/client, or either belongs to another business.")
    @ApiResponse(responseCode = "409", description = "The invoice is not a DRAFT, so it can't be edited.")
    @PutMapping("/{id}")
    InvoiceResponse update(@PathVariable Long id, @Valid @RequestBody InvoiceRequest request) {
        return invoiceService.update(id, request);
    }

    @Operation(summary = "Delete a draft invoice", description = "Only DRAFT invoices can be deleted.")
    @ApiResponse(responseCode = "204", description = "Invoice deleted.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @ApiResponse(responseCode = "409", description = "The invoice is not a DRAFT; it has already been sent.")
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable Long id) {
        invoiceService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Hands a draft to the owner for approval. Deliberately has no
     * {@code @PreAuthorize}: submitting is the staff half of maker-checker, and
     * an owner may submit their own draft too (Product Scope §4).
     */
    @Operation(summary = "Submit a draft for approval", description = "DRAFT -> PENDING_APPROVAL. Any role, "
            + "including the owner submitting their own draft.")
    @ApiResponse(responseCode = "200", description = "Invoice is now PENDING_APPROVAL.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @ApiResponse(responseCode = "409", description = "The invoice cannot transition to PENDING_APPROVAL from its current status.")
    @PostMapping("/{id}/submit")
    InvoiceResponse submit(@PathVariable Long id) {
        return lifecycleService.submit(id);
    }

    /** Issues the invoice to the client, and freezes its GST rate. Owner only. */
    @Operation(summary = "Send an invoice to the client", description = "DRAFT or PENDING_APPROVAL -> SENT. Freezes "
            + "the GST rate snapshot. Owner only.")
    @ApiResponse(responseCode = "200", description = "Invoice is now SENT.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @ApiResponse(responseCode = "409", description = "Illegal transition to SENT, or the invoice has no line items.")
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{id}/send")
    InvoiceResponse send(@PathVariable Long id) {
        return lifecycleService.send(id);
    }

    /** Returns a submitted invoice to DRAFT with a note. Owner only. */
    @Operation(summary = "Reject a submitted invoice", description = "PENDING_APPROVAL -> DRAFT, with a note "
            + "explaining what to fix. Owner only.")
    @ApiResponse(responseCode = "200", description = "Invoice is back to DRAFT, carrying the rejection note.")
    @ApiResponse(responseCode = "400", description = "Validation failed (the note is required).")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @ApiResponse(responseCode = "409", description = "The invoice is not PENDING_APPROVAL, so it cannot be rejected.")
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{id}/reject")
    InvoiceResponse reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request) {
        return lifecycleService.reject(id, request.note());
    }
}
