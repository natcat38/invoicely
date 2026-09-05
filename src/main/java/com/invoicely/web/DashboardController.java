package com.invoicely.web;

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
class DashboardController {

    private final DashboardService dashboardService;

    DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @PreAuthorize("hasRole('OWNER')")
    @GetMapping("/dashboard")
    DashboardResponse get() {
        return dashboardService.get();
    }
}
