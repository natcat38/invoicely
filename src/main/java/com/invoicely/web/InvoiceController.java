package com.invoicely.web;

import com.invoicely.domain.InvoiceStatus;
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
    @GetMapping
    Page<InvoiceSummaryResponse> list(
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) Long clientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {

        // Clamped rather than rejected: a caller asking for 10,000 rows almost
        // certainly wants "lots", and a 400 would help nobody.
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
        return invoiceService.list(status, clientId, pageRequest);
    }

    @GetMapping("/{id}")
    InvoiceResponse get(@PathVariable Long id) {
        return invoiceService.get(id);
    }

    @PutMapping("/{id}")
    InvoiceResponse update(@PathVariable Long id, @Valid @RequestBody InvoiceRequest request) {
        return invoiceService.update(id, request);
    }

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
    @PostMapping("/{id}/submit")
    InvoiceResponse submit(@PathVariable Long id) {
        return lifecycleService.submit(id);
    }

    /** Issues the invoice to the client, and freezes its GST rate. Owner only. */
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{id}/send")
    InvoiceResponse send(@PathVariable Long id) {
        return lifecycleService.send(id);
    }

    /** Returns a submitted invoice to DRAFT with a note. Owner only. */
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping("/{id}/reject")
    InvoiceResponse reject(@PathVariable Long id, @Valid @RequestBody RejectRequest request) {
        return lifecycleService.reject(id, request.note());
    }
}
