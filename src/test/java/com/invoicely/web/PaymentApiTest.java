package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment endpoints, end to end: real HTTP handling, real PostgreSQL, real
 * Flyway schema — the same shape as {@link InvoiceApiTest}.
 *
 * <p>Every scenario here starts from the Tech Scope §2 worked example: two
 * lines totalling 1,420.00, a GST-registered business at 9% (127.80), so a
 * sent invoice's total is 1,547.80. Using the same numbers as the docs means a
 * failing assertion can be checked against them directly.
 *
 * <p>Invoices are pushed to SENT (or OVERDUE) straight through the
 * repositories rather than the {@code /send} endpoint, which Task 5's other
 * agent is changing in parallel — the same approach {@code InvoiceApiTest}
 * already takes for statuses that endpoint does not yet reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class PaymentApiTest {

    private static final BigDecimal NINE_PERCENT = new BigDecimal("0.0900");

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
    private User acmeOwner;
    private User acmeStaff;
    private Client acmeClient;

    @BeforeEach
    void seedAGstRegisteredBusiness() {
        acme = new Business("Acme Renovations");
        acme.setGstRegistered(true);
        acme.setGstRate(NINE_PERCENT);
        acme = businesses.save(acme);
        acmeOwner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        acmeStaff = users.save(new User(acme, "Sam Staff", uniqueEmail(), "hash", Role.STAFF));
        acmeClient = clients.save(new Client(acme, "Bright Cafe"));
    }

    @Test
    @DisplayName("a partial payment reduces the balance and leaves the status alone")
    void partialPaymentLeavesStatusSent() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("500.00", "BANK_TRANSFER", "First instalment")))
                .andExpect(status().isCreated())
                // The id is generated on insert; without an explicit flush it
                // would still be null here, and the Location header would read
                // ".../payments/null".
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.endsWith("null"))))
                .andExpect(jsonPath("$.amount").value(500.00))
                .andExpect(jsonPath("$.method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.note").value("First instalment"))
                .andExpect(jsonPath("$.recordedBy.id").value(acmeOwner.getId()))
                .andExpect(jsonPath("$.recordedBy.name").value("Ada Owner"));

        Invoice reloaded = invoices.findById(invoice.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.SENT);
        assertThat(com.invoicely.domain.InvoiceTotals.of(reloaded).balance())
                .isEqualByComparingTo("1047.80");
    }

    @Test
    @DisplayName("a payment that clears the balance to exactly zero flips the invoice to PAID")
    void fullPaymentAcrossTwoInstalmentsPaysTheInvoice() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("500.00", "BANK_TRANSFER", null)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("1047.80", "PAYNOW", "Final instalment")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1047.80));

        Invoice reloaded = invoices.findById(invoice.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(com.invoicely.domain.InvoiceTotals.of(reloaded).balance())
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("overpaying by one cent is refused with the exact remaining balance in the message")
    void overpayingByOneCentIsRejected() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("1547.81", "CASH", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/payment-exceeds-balance"))
                .andExpect(jsonPath("$.detail")
                        .value("Amount exceeds the remaining balance (S$1,547.80)."));
    }

    @Test
    @DisplayName("a staff token is refused with 403 — staff cannot see or touch money")
    void staffCannotRecordPayments() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(TestTokens.bearer(jwtService, acmeStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("100.00", "CASH", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-role"));

        mockMvc.perform(get("/invoices/" + invoice.getId() + "/payments")
                        .headers(TestTokens.bearer(jwtService, acmeStaff)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a draft has not been issued, so a payment against it is a 409")
    void draftInvoiceCannotTakeAPayment() throws Exception {
        Invoice draft = new Invoice(acme, acmeClient, acmeOwner, "INV-2026-0001",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 3));
        draft.addLineItem("Work", BigDecimal.ONE, new BigDecimal("100.00"));
        draft = invoices.saveAndFlush(draft);

        mockMvc.perform(post("/invoices/" + draft.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("50.00", "CASH", null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/invoice-not-sent"));
    }

    @Test
    @DisplayName("another business's invoice is a 404, never a 403")
    void crossBusinessInvoiceIsNotFound() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        Business other = businesses.save(new Business("Other Contractors"));
        User otherOwner = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(TestTokens.bearer(jwtService, otherOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("50.00", "CASH", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));

        mockMvc.perform(get("/invoices/" + invoice.getId() + "/payments")
                        .headers(TestTokens.bearer(jwtService, otherOwner)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("paying an overdue invoice in full also flips it to PAID")
    void fullPaymentOnOverdueInvoicePaysIt() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");
        invoice.setStatus(InvoiceStatus.OVERDUE);
        invoices.saveAndFlush(invoice);

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("1547.80", "CHEQUE", null)))
                .andExpect(status().isCreated());

        Invoice reloaded = invoices.findById(invoice.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("the payment list comes back newest first")
    void listComesBackNewestFirst() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("500.00", "CASH", "Earlier")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBodyPaidOn("2026-03-15", "600.00", "PAYNOW", "Later")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/invoices/" + invoice.getId() + "/payments").headers(ownerHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].note").value("Later"))
                .andExpect(jsonPath("$[1].note").value("Earlier"));
    }

    @Test
    @DisplayName("recording a payment against an already-PAID invoice is refused, not accepted as a second payment")
    void payingAnAlreadyPaidInvoiceIsRejected() throws Exception {
        Invoice invoice = sentInvoice("INV-2026-0001");

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("1547.80", "BANK_TRANSFER", "Paid in full")))
                .andExpect(status().isCreated());

        Invoice paid = invoices.findById(invoice.getId()).orElseThrow();
        assertThat(paid.getStatus()).isEqualTo(InvoiceStatus.PAID);

        // The invoice is now PAID. A further payment — even a nominal one —
        // must hit PaymentService.record's explicit PAID guard (409), the one
        // branch of the three-part state check with no coverage at all, not
        // the balance-exceeded check (400) sitting right above it.
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                        .headers(ownerHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("0.01", "CASH", "Should be refused")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/invoice-already-paid"));

        assertThat(invoices.findById(invoice.getId()).orElseThrow().getPayments()).hasSize(1);
    }

    @Test
    @DisplayName("a staff token against a draft invoice's payments is 403 for the role, not 409 for the state")
    void staffCannotRecordPaymentsAgainstADraftInvoiceEither() throws Exception {
        // draftInvoiceCannotTakeAPayment already proves an owner gets 409
        // here. A staff token independently fails both the role check (staff
        // may not touch payments at all) and the state check (a draft cannot
        // take a payment) — the same role-answers-first precedence
        // InvoiceLifecycleApiTest.roleIsCheckedBeforeState proves for /send
        // must hold here too, on PaymentController's own @PreAuthorize.
        Invoice draft = new Invoice(acme, acmeClient, acmeOwner, "INV-2026-0002",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 3));
        draft.addLineItem("Work", BigDecimal.ONE, new BigDecimal("100.00"));
        draft = invoices.saveAndFlush(draft);

        mockMvc.perform(post("/invoices/" + draft.getId() + "/payments")
                        .headers(TestTokens.bearer(jwtService, acmeStaff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody("50.00", "CASH", null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-role"));
    }

    /**
     * A SENT invoice matching the Tech Scope §2 worked example: two lines
     * totalling a 1,420.00 subtotal, GST 9% (127.80), total 1,547.80.
     */
    private Invoice sentInvoice(String number) {
        LocalDate issued = LocalDate.of(2026, 2, 1);
        Invoice invoice = new Invoice(acme, acmeClient, acmeOwner, number, issued, issued.plusDays(30));
        invoice.addLineItem("Design", new BigDecimal("2"), new BigDecimal("400.00"));
        invoice.addLineItem("Build", BigDecimal.ONE, new BigDecimal("620.00"));
        invoice.setGstRateSnapshot(NINE_PERCENT);
        invoice.setSentAt(Instant.now());
        invoice.setSentBy(acmeOwner);
        invoice.setStatus(InvoiceStatus.SENT);
        return invoices.saveAndFlush(invoice);
    }

    private String paymentBody(String amount, String method, String note) {
        return paymentBodyPaidOn("2026-03-01", amount, method, note);
    }

    private String paymentBodyPaidOn(String paidAt, String amount, String method, String note) {
        String noteField = note == null ? "null" : "\"" + note + "\"";
        return """
                {
                  "amount": "%s",
                  "paidAt": "%s",
                  "method": "%s",
                  "note": %s
                }
                """.formatted(amount, paidAt, method, noteField);
    }

    private HttpHeaders ownerHeaders() {
        return TestTokens.bearer(jwtService, acmeOwner);
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
