package com.invoicely.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * <p>Requests carry {@code X-Business-Id} and {@code X-User-Id} because this
 * slice runs behind the permit-all security stub; {@link CurrentRequest} reads
 * them today and will read JWT claims from Task 4 without any of these tests
 * changing shape.
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
                        .header("X-Business-Id", other.getId())
                        .header("X-User-Id", otherOwner.getId())
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

        HttpHeaders otherHeaders = new HttpHeaders();
        otherHeaders.add("X-Business-Id", other.getId().toString());
        otherHeaders.add("X-User-Id", otherOwner.getId().toString());

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
    @DisplayName("a request with no caller identity is rejected rather than guessed at")
    void requestsWithoutIdentityAreRejected() throws Exception {
        mockMvc.perform(get("/invoices"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/unidentified-caller"));
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
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Business-Id", acme.getId().toString());
        headers.add("X-User-Id", acmeOwner.getId().toString());
        return headers;
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
