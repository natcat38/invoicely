package com.invoicely.web;

import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner dashboard's four headline stats (Product Scope §2), scoped to the
 * caller's business throughout — see ADR-0001.
 *
 * <p>Every figure comes from an aggregate query in {@link InvoiceRepository}
 * rather than from loading invoices and summing them here: a dashboard that
 * reads the whole invoice history to add up four numbers gets slower every
 * month the business trades. See the query comments on
 * {@code InvoiceRepository} for the shapes chosen and why.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private final InvoiceRepository invoices;
    private final CurrentRequest currentRequest;

    DashboardService(InvoiceRepository invoices, CurrentRequest currentRequest) {
        this.invoices = invoices;
        this.currentRequest = currentRequest;
    }

    DashboardResponse get() {
        Long businessId = currentRequest.businessId();
        // Read once, so every figure below describes the same instant rather
        // than straddling midnight if the request lands at the wrong moment.
        LocalDate today = LocalDate.now();
        LocalDate firstOfThisMonth = today.withDayOfMonth(1);

        BigDecimal outstandingTotal = InvoiceTotals.roundMoney(
                invoices.sumOutstandingBalance(businessId));
        // Overdue is asked by effective status, not by the stored column: the
        // nightly job has not necessarily run since these invoices fell due,
        // and a dashboard that undercounts overdue money for most of a day is
        // worse than one that says nothing.
        BigDecimal overdueAmount = InvoiceTotals.roundMoney(
                invoices.sumOverdueBalance(businessId, today));
        long overdueCount = invoices.countOverdue(businessId, today);
        BigDecimal revenueThisMonth = InvoiceTotals.roundMoney(invoices.sumPaymentsReceived(
                businessId, firstOfThisMonth, firstOfThisMonth.plusMonths(1)));

        // The queue is loaded, not just counted: the owner needs the rows to
        // act on, and the count is simply how many came back — one query
        // answers both parts of this headline stat.
        List<InvoiceSummaryResponse> awaitingApproval = invoices
                .findForList(businessId, InvoiceStatus.PENDING_APPROVAL, null, today,
                        Pageable.unpaged())
                .map(InvoiceSummaryResponse::from)
                .getContent();

        return new DashboardResponse(outstandingTotal, overdueAmount, overdueCount,
                revenueThisMonth, awaitingApproval.size(), awaitingApproval);
    }
}
