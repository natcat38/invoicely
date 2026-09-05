package com.invoicely.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invoicely.TestTokens;
import com.invoicely.TestcontainersConfiguration;
import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Client;
import com.invoicely.domain.ClientRepository;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.PaymentMethod;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.JwtService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /dashboard}, end to end: real HTTP handling, real PostgreSQL,
 * real Flyway schema, real signed JWTs via {@link TestTokens}.
 *
 * <p>Invoices are pushed straight into non-draft states through
 * {@link InvoiceRepository} — {@code setStatus}, {@code setGstRateSnapshot},
 * {@code addPayment} — rather than through {@code /send} or
 * {@code /payments}, which are being built in a parallel slice of this same
 * task and may not exist yet while this test is being written.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class DashboardApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private ClientRepository clients;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private JwtService jwtService;

    private Business acme;
    private User owner;
    private User staff;
    private Client client;

    @BeforeEach
    void seedABusiness() {
        Business business = new Business("Acme Renovations");
        business.setGstRegistered(true);
        // gstRate keeps its 9% default (Business.java) — this test relies on
        // that being the same 9% the Tech Scope's worked example uses.
        acme = businesses.save(business);
        owner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        staff = users.save(new User(acme, "Sam Staff", uniqueEmail(), "hash", Role.STAFF));
        client = clients.save(new Client(acme, "Bright Cafe"));
    }

    @Test
    @DisplayName("the four headline stats match the Tech Scope's worked example, by hand")
    void headlineStatsMatchTheWorkedExample() throws Exception {
        // Tech Scope §2, row 1: SENT, unpaid. Subtotal 1,420.00, GST 9% ->
        // total 1,547.80, so the whole total is outstanding.
        sentInvoice("INV-2026-0001", BigDecimal.ZERO, null);
        // Row 2: SENT, partially paid 500.00 this month -> balance 1,047.80.
        // Marked OVERDUE, so it also counts toward the overdue figures.
        Invoice overdue = sentInvoice("INV-2026-0002", new BigDecimal("500.00"), LocalDate.now());
        overdue.setStatus(InvoiceStatus.OVERDUE);
        invoices.saveAndFlush(overdue);
        // Row 3: fully paid -> PAID, so it contributes nothing outstanding.
        // Its payment predates this month, proving revenue does not pick it up.
        Invoice paid = sentInvoice("INV-2026-0003", new BigDecimal("1547.80"), LocalDate.now().minusMonths(2));
        paid.setStatus(InvoiceStatus.PAID);
        invoices.saveAndFlush(paid);
        // Waiting for the owner: the fourth headline stat, and its queue.
        pendingInvoice("INV-2026-0004");
        // A draft affects none of the four stats.
        draftInvoice("INV-2026-0005");

        mockMvc.perform(get("/dashboard").headers(ownerAuth()))
                .andExpect(status().isOk())
                // 1,547.80 (row 1) + 1,047.80 (row 2's balance) = 2,595.60.
                .andExpect(jsonPath("$.outstandingTotal").value(2595.60))
                .andExpect(jsonPath("$.overdueAmount").value(1047.80))
                .andExpect(jsonPath("$.overdueCount").value(1))
                .andExpect(jsonPath("$.revenueThisMonth").value(500.00))
                .andExpect(jsonPath("$.awaitingApprovalCount").value(1))
                .andExpect(jsonPath("$.awaitingApprovalQueue.length()").value(1))
                .andExpect(jsonPath("$.awaitingApprovalQueue[0].number").value("INV-2026-0004"));
    }

    @Test
    @DisplayName("a staff token is forbidden on the dashboard — it is owner-only")
    void staffIsForbidden() throws Exception {
        mockMvc.perform(get("/dashboard").headers(staffAuth()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a brand-new business with no invoices sees zeros, not nulls")
    void newBusinessSeesZeros() throws Exception {
        mockMvc.perform(get("/dashboard").headers(ownerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingTotal").value(0))
                .andExpect(jsonPath("$.overdueAmount").value(0))
                .andExpect(jsonPath("$.overdueCount").value(0))
                .andExpect(jsonPath("$.revenueThisMonth").value(0))
                .andExpect(jsonPath("$.awaitingApprovalCount").value(0))
                .andExpect(jsonPath("$.awaitingApprovalQueue").isArray())
                .andExpect(jsonPath("$.awaitingApprovalQueue.length()").value(0));
    }

    @Test
    @DisplayName("another business's invoices never appear in the figures")
    void otherBusinessIsInvisible() throws Exception {
        Business other = new Business("Other Contractors");
        other.setGstRegistered(true);
        other = businesses.save(other);
        User otherOwner = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));
        Client otherClient = clients.save(new Client(other, "Their Client"));

        Invoice theirs = new Invoice(other, otherClient, otherOwner, "INV-2026-0001",
                LocalDate.now(), LocalDate.now().plusDays(30));
        theirs.addLineItem("Work", BigDecimal.ONE, new BigDecimal("999.00"));
        theirs.setGstRateSnapshot(other.getGstRate());
        theirs.setStatus(InvoiceStatus.SENT);
        invoices.saveAndFlush(theirs);

        mockMvc.perform(get("/dashboard").headers(ownerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingTotal").value(0))
                .andExpect(jsonPath("$.awaitingApprovalQueue.length()").value(0));
    }

    @Test
    @DisplayName("revenue only counts payments received this calendar month")
    void revenueCountsOnlyThisMonth() throws Exception {
        sentInvoice("INV-2026-0001", new BigDecimal("200.00"), LocalDate.now());
        sentInvoice("INV-2026-0002", new BigDecimal("300.00"), LocalDate.now().minusMonths(1));

        mockMvc.perform(get("/dashboard").headers(ownerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revenueThisMonth").value(200.00));
    }

    @Test
    @DisplayName("the queue lists exactly the invoices waiting for approval")
    void queueListsExactlyPendingApproval() throws Exception {
        pendingInvoice("INV-2026-0001");
        pendingInvoice("INV-2026-0002");
        draftInvoice("INV-2026-0003");
        sentInvoice("INV-2026-0004", BigDecimal.ZERO, null);

        mockMvc.perform(get("/dashboard").headers(ownerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingApprovalQueue.length()").value(2))
                .andExpect(jsonPath("$.awaitingApprovalQueue[*].number")
                        .value(containsInAnyOrder("INV-2026-0001", "INV-2026-0002")));
    }

    /**
     * A SENT invoice matching the Tech Scope §2 worked example's line items
     * (subtotal 1,420.00), optionally with a payment recorded against it.
     */
    private Invoice sentInvoice(String number, BigDecimal paymentAmount, LocalDate paidAt) {
        Invoice invoice = new Invoice(acme, client, owner, number, LocalDate.now(), LocalDate.now().plusDays(30));
        invoice.addLineItem("Design", new BigDecimal("2"), new BigDecimal("400.00"));
        invoice.addLineItem("Build", BigDecimal.ONE, new BigDecimal("620.00"));
        invoice.setGstRateSnapshot(acme.getGstRate());
        invoice.setStatus(InvoiceStatus.SENT);
        if (paymentAmount.signum() > 0) {
            invoice.addPayment(paymentAmount, paidAt, PaymentMethod.BANK_TRANSFER, owner);
        }
        return invoices.saveAndFlush(invoice);
    }

    private Invoice pendingInvoice(String number) {
        Invoice invoice = new Invoice(acme, client, staff, number, LocalDate.now(), LocalDate.now().plusDays(30));
        invoice.addLineItem("Work", BigDecimal.ONE, new BigDecimal("100.00"));
        invoice.setStatus(InvoiceStatus.PENDING_APPROVAL);
        return invoices.saveAndFlush(invoice);
    }

    private Invoice draftInvoice(String number) {
        Invoice invoice = new Invoice(acme, client, staff, number, LocalDate.now(), LocalDate.now().plusDays(30));
        invoice.addLineItem("Work", BigDecimal.ONE, new BigDecimal("50.00"));
        return invoices.saveAndFlush(invoice);
    }

    private HttpHeaders ownerAuth() {
        return TestTokens.bearer(jwtService, owner);
    }

    private HttpHeaders staffAuth() {
        return TestTokens.bearer(jwtService, staff);
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
