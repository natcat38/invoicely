package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invoicely.TestTokens;
import com.invoicely.TestcontainersConfiguration;
import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessCalendar;
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
 * The role half of the lifecycle rules, and the maker-checker round trip end to
 * end.
 *
 * <p>The state half — which transitions exist at all — is checked cell by cell
 * in {@code InvoiceStatusTest}, without Spring. What can only be tested here is
 * who is allowed to drive each one, and what the two kinds of refusal look like
 * on the wire: <b>403</b> for the wrong role, <b>409</b> for the wrong state.
 * When a request is both, the role check answers first, because it runs before
 * the method body.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InvoiceLifecycleApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private ClientRepository clients;

    @Autowired
    private InvoiceRepository invoices;

    private Business acme;
    private User owner;
    private User staff;
    private Client client;

    @BeforeEach
    void seedABusinessWithBothRoles() {
        acme = businesses.save(new Business("Acme Renovations"));
        owner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        staff = users.save(new User(acme, "Sam Staff", uniqueEmail(), "hash", Role.STAFF));
        client = clients.save(new Client(acme, "Bright Cafe"));
    }

    @Test
    @DisplayName("the whole maker-checker round trip: submit, reject, resubmit, approve and send")
    void theMakerCheckerRoundTrip() throws Exception {
        Invoice invoice = draft();

        // Staff submits. Any role may, which is why there is no @PreAuthorize.
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/submit").headers(as(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        // Owner sends it back with a reason.
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/reject").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note": "The second line is billed at last year's rate."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rejectionNote")
                        .value("The second line is billed at last year's rate."));

        // Staff fixes it and resubmits. The old note must not follow it back.
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/submit").headers(as(staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.rejectionNote").doesNotExist());

        // Owner approves and sends.
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/send").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.sentAt").exists());
    }

    @Test
    @DisplayName("staff may not send or reject — 403, whatever state the invoice is in")
    void staffCannotSendOrReject() throws Exception {
        Invoice invoice = draft();

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/send").headers(as(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-role"));

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/reject").headers(as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note": "no"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the role check answers before the state check when a request fails both")
    void roleIsCheckedBeforeState() throws Exception {
        // Already sent, so sending again is an illegal transition too. Staff
        // should still be told about their role rather than about the invoice:
        // the state of a record they may not touch is not their business.
        Invoice sent = statusOf(draft(), InvoiceStatus.SENT);

        mockMvc.perform(post("/invoices/" + sent.getId() + "/send").headers(as(staff)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/invoices/" + sent.getId() + "/send").headers(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/illegal-status-transition"));
    }

    @Test
    @DisplayName("an illegal transition names the status the invoice is actually in")
    void illegalTransitionsExplainThemselves() throws Exception {
        Invoice paid = statusOf(draft(), InvoiceStatus.PAID);

        mockMvc.perform(post("/invoices/" + paid.getId() + "/submit").headers(as(staff)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "This invoice is paid, so it cannot be pending approval."));
    }

    @Test
    @DisplayName("rejecting requires a note")
    void rejectingRequiresANote() throws Exception {
        Invoice submitted = statusOf(draft(), InvoiceStatus.PENDING_APPROVAL);

        mockMvc.perform(post("/invoices/" + submitted.getId() + "/reject").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"note": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'note')]").exists());
    }

    @Test
    @DisplayName("sending freezes the GST rate, and a later settings change cannot move it")
    void sendingSnapshotsTheGstRate() throws Exception {
        acme.setGstRegistered(true);
        acme.setGstRate(new BigDecimal("0.0900"));
        businesses.saveAndFlush(acme);

        Invoice invoice = draft();
        mockMvc.perform(post("/invoices/" + invoice.getId() + "/send").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRate").value(0.0900))
                .andExpect(jsonPath("$.gst").value(9.00))
                .andExpect(jsonPath("$.total").value(109.00));

        // The business re-registers at a different rate afterwards.
        acme.setGstRate(new BigDecimal("0.1100"));
        businesses.saveAndFlush(acme);

        assertThat(invoices.findById(invoice.getId()).orElseThrow().getGstRateSnapshot())
                .as("the client already has this document")
                .isEqualByComparingTo("0.0900");
    }

    @Test
    @DisplayName("an unregistered business that later registers does not grow a GST line retroactively")
    void anUnregisteredBusinessSendsWithoutGstForGood() throws Exception {
        Invoice invoice = draft();

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/send").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRate").doesNotExist())
                .andExpect(jsonPath("$.total").value(100.00));

        // Registering for GST tomorrow must not rewrite an invoice already out.
        acme.setGstRegistered(true);
        acme.setGstRate(new BigDecimal("0.0900"));
        businesses.saveAndFlush(acme);

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/submit").headers(as(owner)))
                .andExpect(status().isConflict());
        assertThat(invoices.findById(invoice.getId()).orElseThrow().getGstRateSnapshot()).isNull();
    }

    @Test
    @DisplayName("a brand-new invoice created after GST registration picks up the new rate")
    void aNewInvoiceAfterRegistrationChargesGst() throws Exception {
        // anUnregisteredBusinessSendsWithoutGstForGood proves an *existing*
        // invoice does not retroactively gain GST when the business registers
        // later. This proves the other half of that toggle: a fresh invoice
        // created and sent *after* registration does charge it, so the
        // snapshot logic is not accidentally "sticky" in the other direction
        // (e.g. via a stale value cached on the business rather than read live
        // for a not-yet-sent invoice).
        Invoice beforeRegistration = draft();
        mockMvc.perform(post("/invoices/" + beforeRegistration.getId() + "/send").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRate").doesNotExist());

        acme.setGstRegistered(true);
        acme.setGstRate(new BigDecimal("0.0900"));
        businesses.saveAndFlush(acme);

        Invoice afterRegistration = draft();
        mockMvc.perform(post("/invoices/" + afterRegistration.getId() + "/send").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRate").value(0.0900))
                .andExpect(jsonPath("$.gst").value(9.00))
                .andExpect(jsonPath("$.total").value(109.00));
    }

    @Test
    @DisplayName("an invoice with no lines cannot be sent")
    void anEmptyInvoiceCannotBeSent() throws Exception {
        LocalDate issued = BusinessCalendar.today();
        Invoice empty = invoices.saveAndFlush(
                new Invoice(acme, client, owner, "INV-2026-9001", issued, issued.plusDays(30)));

        mockMvc.perform(post("/invoices/" + empty.getId() + "/send").headers(as(owner)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/invoice-has-no-lines"));
    }

    @Test
    @DisplayName("another business cannot drive this invoice, and gets 404 rather than 403")
    void otherBusinessesGetNotFound() throws Exception {
        Invoice invoice = draft();
        Business other = businesses.save(new Business("Other Contractors"));
        User otherOwner = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));

        mockMvc.perform(post("/invoices/" + invoice.getId() + "/send").headers(as(otherOwner)))
                .andExpect(status().isNotFound());
    }

    /**
     * A one-line DRAFT worth 100.00, created by the staff member.
     *
     * <p>Dated relative to today rather than pinned to a fixed date: an invoice
     * issued in the past would already be past its due date, and the API
     * computes OVERDUE on read, so a "was it sent?" assertion would fail for a
     * reason that has nothing to do with sending.
     */
    private Invoice draft() {
        LocalDate issued = BusinessCalendar.today();
        Invoice invoice = new Invoice(acme, client, staff,
                "INV-2026-" + UUID.randomUUID().toString().substring(0, 4),
                issued, issued.plusDays(30));
        invoice.addLineItem("Work", BigDecimal.ONE, new BigDecimal("100.00"));
        return invoices.saveAndFlush(invoice);
    }

    private Invoice statusOf(Invoice invoice, InvoiceStatus status) {
        invoice.setStatus(status);
        return invoices.saveAndFlush(invoice);
    }

    private HttpHeaders as(User user) {
        return TestTokens.bearer(jwtService, user);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
