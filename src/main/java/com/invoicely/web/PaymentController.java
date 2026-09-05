package com.invoicely.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment endpoints for one invoice. Kept apart from
 * {@link InvoiceController} — another agent owns that class in parallel — but
 * also on its own merits: payments are owner-only end to end (Product Scope
 * §3: staff cannot see money), which is a different shape from the mixed
 * roles the invoice endpoints carry.
 *
 * <p>Like {@link TeamController}, each method carries its own
 * {@code @PreAuthorize} rather than one class-level annotation, so the rule
 * for a given method sits right next to it.
 */
@RestController
@RequestMapping("/invoices/{invoiceId}/payments")
class PaymentController {

    private final PaymentService paymentService;

    PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PreAuthorize("hasRole('OWNER')")
    @PostMapping
    ResponseEntity<PaymentResponse> record(@PathVariable Long invoiceId,
                                            @Valid @RequestBody RecordPaymentRequest request) {
        PaymentResponse recorded = paymentService.record(invoiceId, request);
        return ResponseEntity
                .created(URI.create("/invoices/" + invoiceId + "/payments/" + recorded.id()))
                .body(recorded);
    }

    @PreAuthorize("hasRole('OWNER')")
    @GetMapping
    List<PaymentResponse> list(@PathVariable Long invoiceId) {
        return paymentService.list(invoiceId);
    }
}
