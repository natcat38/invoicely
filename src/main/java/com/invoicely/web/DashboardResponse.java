package com.invoicely.web;

import java.math.BigDecimal;
import java.util.List;

/**
 * The owner dashboard's four headline stats (Product Scope §2):
 *
 * <ul>
 *   <li><b>outstanding total</b> — remaining balance across every issued,
 *       unpaid invoice (SENT + OVERDUE)
 *   <li><b>overdue amount / count</b> — the same, restricted to OVERDUE
 *   <li><b>revenue this month</b> — payments actually received this calendar
 *       month, not what was invoiced
 *   <li><b>awaiting-approval count</b>, alongside the queue itself, so the
 *       owner can act on it without a second request
 * </ul>
 *
 * <p>Every amount is zero, and every count is zero, for a business with
 * nothing to report — never null. See {@link DashboardService}.
 */
public record DashboardResponse(
        BigDecimal outstandingTotal,
        BigDecimal overdueAmount,
        long overdueCount,
        BigDecimal revenueThisMonth,
        long awaitingApprovalCount,
        List<InvoiceSummaryResponse> awaitingApprovalQueue) {
}
