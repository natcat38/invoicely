package com.invoicely.web;

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
class SettingsController {

    private final SettingsService settingsService;

    SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @PreAuthorize("hasRole('OWNER')")
    @GetMapping
    SettingsResponse get() {
        return settingsService.get();
    }

    /** A full replace rather than a patch — the Settings page submits every field. */
    @PreAuthorize("hasRole('OWNER')")
    @PutMapping
    SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
        return settingsService.update(request);
    }
}
