package com.invoicely.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Business and GST settings. Owner-only, per the Product Scope §3 matrix —
 * staff may not change what the business charges.
 *
 * <p>There is no id in the path: the only business a caller can reach is their
 * own, and it comes from their token (ADR-0001).
 */
@RestController
@RequestMapping("/settings")
@Tag(name = "Settings", description = "The caller's own business settings: name, GST, payment terms. Owner-only.")
class SettingsController {

    private final SettingsService settingsService;

    SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Operation(summary = "Get business settings")
    @ApiResponse(responseCode = "200", description = "Current settings, including the invoice letterhead (address, UEN).")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @PreAuthorize("hasRole('OWNER')")
    @GetMapping
    SettingsResponse get() {
        return settingsService.get();
    }

    /** A full replace rather than a patch — the Settings page submits every field. */
    @Operation(summary = "Replace business settings", description = "Full replace — the Settings page submits every field. "
            + "address and uen are optional and feed the invoice letterhead (ADR-0011); "
            + "they are read live by every invoice document, so a change here changes what an already-sent invoice prints.")
    @ApiResponse(responseCode = "200", description = "Settings updated.")
    @ApiResponse(responseCode = "400", description = "Validation failed, or defaultPaymentTermsDays is not 7, 14 or 30.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @PreAuthorize("hasRole('OWNER')")
    @PutMapping
    SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
        return settingsService.update(request);
    }
}
