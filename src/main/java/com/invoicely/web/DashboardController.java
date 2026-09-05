package com.invoicely.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /dashboard} — the owner's landing page (Product Scope §2). Staff
 * land on the invoice list instead; they may not see revenue at all
 * (Product Scope §3), which is why this whole endpoint is owner-only rather
 * than trimming the response per role.
 */
@RestController
@Tag(name = "Dashboard", description = "The owner's landing page: revenue and what needs attention.")
class DashboardController {

    private final DashboardService dashboardService;

    DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @Operation(summary = "Get the owner dashboard", description = "Owner-only; staff may not see revenue at all.")
    @ApiResponse(responseCode = "200", description = "Dashboard data.")
    @ApiResponse(responseCode = "403", description = "Caller is STAFF, not OWNER.")
    @PreAuthorize("hasRole('OWNER')")
    @GetMapping("/dashboard")
    DashboardResponse get() {
        return dashboardService.get();
    }
}
