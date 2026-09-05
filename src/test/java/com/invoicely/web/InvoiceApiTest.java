package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invoice endpoints, end to end: real HTTP handling, real PostgreSQL, real
 * Flyway schema.
 *
 * <p>Requests carry a real {@code Authorization: Bearer} token, signed by the
 * application's own {@link JwtService} via {@link TestTokens#bearer}, because
 * Task 4 replaced the permit-all security stub with real authentication.
 * {@link CurrentRequest} reads the {@code sub}/{@code biz}/{@code role} claims
 * off that token instead of the old development headers.
 *
 * <p>Request bodies are written as JSON text blocks rather than built from
 * objects. It is a little more typing, but the test then asserts against the
 * wire format an actual client would send, instead of against a serialiser
 * agreeing with itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class InvoiceApiTest {

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
    private Client acmeClient;

    @BeforeEach
    void seedABusiness() {
        acme = businesses.save(new Business("Acme Renovations"));
        acmeOwner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        acmeClient = clients.save(new Client(acme, "Bright Cafe"));
    }

    @Test
    @DisplayName("creating an invoice numbers it and defaults the due date to the payment terms")
    void createNumbersTheInvoiceAndDefaultsTheDueDate() throws Exception {
        mockMvc.perform(post("/invoices").headers(acmeHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": %d,
                                  "issueDate": "2026-02-01",
                                  "lineItems": [
                                    {"description": "Design", "quantity": "2", "unitPrice": "400.00"},
                                    {"description": "Build",  "quantity": "1", "unitPrice": "620.00"}
                                  ]
                                }
                                """.formatted(acmeClient.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value("INV-2026-0001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                // The business defaults to 30-day terms and no due date was sent.
                .andExpect(jsonPath("$.dueDate").value("2026-03-03"))
                .andExpect(jsonPath("$.subtotal").value(1420.00))
                .andExpect(jsonPath("$.gstRate").doesNotExist())
                .andExpect(jsonPath("$.total").value(1420.00))
                .andExpect(jsonPath("$.balance").value(1420.00))
                .andExpect(jsonPath("$.lineItems.length()").value(2));
    }

    @Test
    @DisplayName("numbering runs per business, so each one starts at 0001")
    void numberingIsPerBusiness() throws Exception {
        createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001");
        createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0002");

        Business other = businesses.save(new Business("Other Contractors"));
        User otherOwner = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));
        Client otherClient = clients.save(new Client(other, "Their Client"));

        mockMvc.perform(post("/invoices")
                        .headers(TestTokens.bearer(jwtService, otherOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneLineBody(otherClient.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number")
                        .value("INV-" + LocalDate.now().getYear() + "-0001"));
    }

    @Test
    @DisplayName("the list puts overdue first, then waiting for approval, then the newest")
    void listIsOrderedByWhatNeedsAttention() throws Exception {
        // Created oldest-first, so a plain "newest first" sort would reverse
        // this. Only the status priority puts the overdue one at the top.
        Invoice paid = statusOf(createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001"), InvoiceStatus.PAID);
        Invoice overdue = statusOf(createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0002"), InvoiceStatus.OVERDUE);
        Invoice pending = statusOf(createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0003"), InvoiceStatus.PENDING_APPROVAL);
        Invoice newestDraft = createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0004");

        mockMvc.perform(get("/invoices").headers(acmeHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(4))
                .andExpect(jsonPath("$.content[0].id").value(overdue.getId()))
                .andExpect(jsonPath("$.content[1].id").value(pending.getId()))
                // Then everything else, newest first.
                .andExpect(jsonPath("$.content[2].id").value(newestDraft.getId()))
                .andExpect(jsonPath("$.content[3].id").value(paid.getId()));
    }

    @Test
    @DisplayName("the list can be filtered by status and by client")
    void listCanBeFiltered() throws Exception {
        statusOf(createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001"), InvoiceStatus.OVERDUE);
        createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0002");
        Client secondClient = clients.save(new Client(acme, "Second Client"));
        createInvoice(acme, acmeOwner, secondClient, "INV-2026-0003");

        mockMvc.perform(get("/invoices").param("status", "OVERDUE").headers(acmeHeaders()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].number").value("INV-2026-0001"));

        mockMvc.perform(get("/invoices").param("clientId", secondClient.getId().toString())
                        .headers(acmeHeaders()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].number").value("INV-2026-0003"));
    }

    @Test
    @DisplayName("another business gets a 404, never a 403, for an invoice it does not own")
    void invoicesOfOtherBusinessesAreInvisible() throws Exception {
        Invoice invoice = createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001");
        Business other = businesses.save(new Business("Other Contractors"));
        User otherOwner = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));

        HttpHeaders otherHeaders = TestTokens.bearer(jwtService, otherOwner);

        mockMvc.perform(get("/invoices/" + invoice.getId()).headers(otherHeaders))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
        mockMvc.perform(delete("/invoices/" + invoice.getId()).headers(otherHeaders))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/invoices/" + invoice.getId()).headers(otherHeaders)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneLineBody(acmeClient.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an invoice created by a token always records that token's own user as created_by")
    void createdByMustBelongToTheSameBusiness() throws Exception {
        // Before Task 4, X-Business-Id and X-User-Id were sent as separate
        // headers, so a caller in Acme could name a user from another
        // business as created_by; InvoiceService's
        // UserRepository.findByIdAndBusinessId lookup guarded against that
        // by 404ing. With bearer tokens, sub (the user) and biz (the
        // business) both come from one signed token, so that mismatched
        // combination can no longer be expressed over HTTP at all. What is
        // still true, and still worth proving, is that created_by is always
        // the caller's own id from the token — never something the request
        // body could influence — so the guard's lookup is exercised here
        // with a same-business identity instead.
        String location = mockMvc.perform(post("/invoices")
                        .headers(TestTokens.bearer(jwtService, acmeOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneLineBody(acmeClient.getId())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader("Location");

        Long invoiceId = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
        Invoice saved = invoices.findById(invoiceId).orElseThrow();
        assertThat(saved.getCreatedBy().getId()).isEqualTo(acmeOwner.getId());
    }

    @Test
    @DisplayName("a sent invoice cannot be edited or deleted")
    void onlyDraftsAreEditable() throws Exception {
        Invoice sent = statusOf(createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001"), InvoiceStatus.SENT);

        mockMvc.perform(put("/invoices/" + sent.getId()).headers(acmeHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oneLineBody(acmeClient.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/invoice-not-draft"))
                .andExpect(jsonPath("$.detail")
                        .value("Sent invoices can't be edited. Create a new invoice."));

        mockMvc.perform(delete("/invoices/" + sent.getId()).headers(acmeHeaders()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("editing a draft replaces its lines and recomputes the totals")
    void editingADraftReplacesItsLines() throws Exception {
        Invoice draft = createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001");

        mockMvc.perform(put("/invoices/" + draft.getId()).headers(acmeHeaders())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": %d,
                                  "lineItems": [
                                    {"description": "Only line", "quantity": "3", "unitPrice": "50.00"}
                                  ]
                                }
                                """.formatted(acmeClient.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value("INV-2026-0001"))
                .andExpect(jsonPath("$.lineItems.length()").value(1))
                .andExpect(jsonPath("$.total").value(150.00));
    }

    @Test
    @DisplayName("a fetched invoice carries the bill-to block, the business letterhead and amountPaid")
    void fetchedInvoiceCarriesTheDocumentContract() throws Exception {
        // The bill-to and letterhead blocks are read live off the client/business
        // rows (ADR-0011), so setting them here is enough to prove the response
        // reflects today's data rather than anything copied at invoice creation.
        acmeClient.setAddress("1 Cafe Street, Singapore 123456");
        acmeClient.setContactPerson("Chloe Chua");
        acmeClient.setEmail("chloe@brightcafe.example");
        clients.saveAndFlush(acmeClient);

        acme.setAddress("1 Renovation Row, Singapore 654321");
        acme.setUen("201234567A");
        businesses.saveAndFlush(acme);

        Invoice invoice = createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001");
        invoice.addPayment(new BigDecimal("40.00"), LocalDate.of(2026, 2, 5), PaymentMethod.PAYNOW, acmeOwner);
        // flush(), not saveAndFlush(invoice). createInvoice already persisted
        // this invoice, so it is managed by the current transaction and
        // Hibernate will write the new payment out on its own. Passing it back
        // through save() would call merge() instead, and Invoice's payments
        // collection cascades PERSIST only — merge is not cascaded to it, so
        // the payment would be inserted as an empty row and fail its
        // invoice_id not-null constraint. Other tests here can use
        // saveAndFlush because their invoice is still transient at that point.
        invoices.flush();

        mockMvc.perform(get("/invoices/" + invoice.getId()).headers(acmeHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client.address").value("1 Cafe Street, Singapore 123456"))
                .andExpect(jsonPath("$.client.contactPerson").value("Chloe Chua"))
                .andExpect(jsonPath("$.client.email").value("chloe@brightcafe.example"))
                .andExpect(jsonPath("$.business.name").value("Acme Renovations"))
                .andExpect(jsonPath("$.business.address").value("1 Renovation Row, Singapore 654321"))
                .andExpect(jsonPath("$.business.uen").value("201234567A"))
                .andExpect(jsonPath("$.business.gstRegistered").value(false))
                // 100.00 line total, 40.00 paid.
                .andExpect(jsonPath("$.amountPaid").value(40.00))
                .andExpect(jsonPath("$.balance").value(60.00));
    }

    @Test
    @DisplayName("amountPaid is 0.00, not null, on an unpaid invoice, and a business's letterhead is null, not blank, before it sets one")
    void unpaidInvoiceAndUnsetLetterheadRenderAsExplicitNullsNotBlanks() throws Exception {
        // acme and acmeClient are seeded with neither an address, a UEN nor any
        // client contact details, and this invoice has had no payment recorded.
        // The document must be able to tell "not set" apart from "set to
        // empty" so it knows to omit the letterhead row rather than print a
        // blank line — asserting doesNotExist proves the field comes back as
        // JSON null (or omitted), never "".
        Invoice invoice = createInvoice(acme, acmeOwner, acmeClient, "INV-2026-0001");

        mockMvc.perform(get("/invoices/" + invoice.getId()).headers(acmeHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountPaid").value(0.00))
                .andExpect(jsonPath("$.business.address").doesNotExist())
                .andExpect(jsonPath("$.business.uen").doesNotExist())
                .andExpect(jsonPath("$.client.address").doesNotExist())
                .andExpect(jsonPath("$.client.contactPerson").doesNotExist())
                .andExpect(jsonPath("$.client.email").doesNotExist());
    }

    @Test
    @DisplayName("an invalid body comes back as a 400 naming every field that failed")
    void validationFailuresListTheirFields() throws Exception {
        mockMvc.perform(post("/invoices").headers(acmeHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId": null, "lineItems": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/validation-failed"))
                .andExpect(jsonPath("$.errors[?(@.field == 'clientId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'lineItems')]").exists());
    }

    @Test
    @DisplayName("a due date before the issue date is rejected with an explanation")
    void dueDateCannotPrecedeIssueDate() throws Exception {
        mockMvc.perform(post("/invoices").headers(acmeHeaders()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientId": %d,
                                  "issueDate": "2026-02-10",
                                  "dueDate": "2026-02-01",
                                  "lineItems": [
                                    {"description": "Work", "quantity": "1", "unitPrice": "10.00"}
                                  ]
                                }
                                """.formatted(acmeClient.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/due-before-issue"));
    }

    @Test
    @DisplayName("a request with no bearer token is rejected by the security filter chain")
    void requestsWithoutIdentityAreRejected() throws Exception {
        // Before Task 4 this reached CurrentRequest and came back as a
        // Problem Detail with type /problems/unidentified-caller. Now Spring
        // Security's filter chain rejects the request before the
        // application code — including GlobalExceptionHandler — ever runs,
        // so only the 401 status is guaranteed; the body is whatever Spring
        // Security's default entry point writes, not our Problem Detail.
        mockMvc.perform(get("/invoices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("ping answers without a database or a caller identity")
    void pingIsOpen() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    /** Saves an invoice straight through the repository, bypassing the API. */
    private Invoice createInvoice(Business business, User owner, Client client, String number) {
        LocalDate issued = LocalDate.of(2026, 2, 1);
        Invoice invoice = new Invoice(business, client, owner, number, issued, issued.plusDays(30));
        invoice.addLineItem("Work", BigDecimal.ONE, new BigDecimal("100.00"));
        return invoices.saveAndFlush(invoice);
    }

    /**
     * Sets a status directly. The endpoints that move an invoice between
     * statuses arrive in Task 5; until then this is how a test reaches a
     * non-draft state.
     */
    private Invoice statusOf(Invoice invoice, InvoiceStatus status) {
        invoice.setStatus(status);
        return invoices.saveAndFlush(invoice);
    }

    private String oneLineBody(Long clientId) {
        return """
                {
                  "clientId": %d,
                  "lineItems": [{"description": "Work", "quantity": "1", "unitPrice": "100.00"}]
                }
                """.formatted(clientId);
    }

    private HttpHeaders acmeHeaders() {
        return TestTokens.bearer(jwtService, acmeOwner);
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
