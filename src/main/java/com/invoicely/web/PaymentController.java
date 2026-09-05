package com.invoicely.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Payments", description = "Recording money against a sent invoice. Owner-only throughout.")
class PaymentController {

    private final PaymentService paymentService;

    PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Operation(summary = "Record a payment", description = "A payment that brings the balance to exactly zero "
            + "flips the invoice to PAID. Owner only.")
    @ApiResponse(responseCode = "201", description = "Payment recorded.")
    @ApiResponse(responseCode = "400", description = "Validation failed, or the amount exceeds the remaining balance.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @ApiResponse(responseCode = "409", description = "The invoice has not been sent, or is already fully paid.")
    @PreAuthorize("hasRole('OWNER')")
    @PostMapping
    ResponseEntity<PaymentResponse> record(@PathVariable Long invoiceId,
                                            @Valid @RequestBody RecordPaymentRequest request) {
        PaymentResponse recorded = paymentService.record(invoiceId, request);
        return ResponseEntity
                .created(URI.create("/invoices/" + invoiceId + "/payments/" + recorded.id()))
                .body(recorded);
    }

    @Operation(summary = "List payments on an invoice", description = "Newest first. Owner only.")
    @ApiResponse(responseCode = "200", description = "Payments for this invoice.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @ApiResponse(responseCode = "404", description = "No such invoice, or it belongs to another business.")
    @PreAuthorize("hasRole('OWNER')")
    @GetMapping
    List<PaymentResponse> list(@PathVariable Long invoiceId) {
        return paymentService.list(invoiceId);
    }
}
