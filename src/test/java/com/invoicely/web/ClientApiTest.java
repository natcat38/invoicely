package com.invoicely.web;

import static org.hamcrest.Matchers.containsString;
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
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end proof of the {@code /clients} endpoints, driven through MockMvc
 * against a real PostgreSQL (Testcontainers) — Task 3 has no security yet, so
 * identity comes from the {@code X-Business-Id}/{@code X-User-Id} development
 * headers {@link CurrentRequest} reads. See its Javadoc for why that is safe
 * only until Task 4.
 *
 * <p>{@code @Transactional} wraps each test in a transaction that is rolled
 * back afterwards, the same trick {@code DomainPersistenceTest} uses. MockMvc
 * runs the request synchronously on the test thread, so it shares that
 * transaction — which also means every test can reuse the same owner email
 * without tripping the case-insensitive unique index between test methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class ClientApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businessRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    private Business acme;
    private Business otherBusiness;
    private Long acmeOwnerId;

    @BeforeEach
    void seedTwoBusinesses() {
        acme = businessRepository.save(new Business("Acme Renovations"));
        acmeOwnerId = userRepository
                .save(new User(acme, "Ada Owner", "ada@acme.example", "hash", Role.OWNER))
                .getId();

        // A second business exists purely to prove cross-business isolation:
        // it never appears in the "happy path" assertions.
        otherBusiness = businessRepository.save(new Business("Other Contractors"));
    }

    @Test
    @DisplayName("a created client round-trips through GET with everything it was given")
    void createThenReadRoundTrip() throws Exception {
        String body = objectMapper.writeValueAsString(new ClientRequest(
                "Bright Cafe", "Bao Lin", "bao@brightcafe.example", "+65 9123 4567",
                "1 Cafe Street, Singapore", "T09LL1234A", "PayNow to +65 9123 4567", false));

        String location = mockMvc.perform(asBusiness(post("/clients"), acme, acmeOwnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Bright Cafe"))
                .andExpect(jsonPath("$.archived").value(false))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(asBusiness(get(location), acme, acmeOwnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Bright Cafe"))
                .andExpect(jsonPath("$.contactPerson").value("Bao Lin"))
                .andExpect(jsonPath("$.uen").value("T09LL1234A"));
    }

    @Test
    @DisplayName("a blank name is rejected with a 400 that names the field")
    void blankNameFailsValidation() throws Exception {
        String body = objectMapper.writeValueAsString(
                new ClientRequest("   ", null, null, null, null, null, null, false));

        mockMvc.perform(asBusiness(post("/clients"), acme, acmeOwnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    @DisplayName("a client belonging to another business is invisible to GET, PUT and DELETE")
    void crossBusinessAccessIsNotFound() throws Exception {
        Client acmeClient = clientRepository.save(new Client(acme, "Bright Cafe"));
        String body = objectMapper.writeValueAsString(
                new ClientRequest("Renamed", null, null, null, null, null, null, false));

        mockMvc.perform(asBusiness(get("/clients/" + acmeClient.getId()), otherBusiness, acmeOwnerId))
                .andExpect(status().isNotFound());
        mockMvc.perform(asBusiness(put("/clients/" + acmeClient.getId()), otherBusiness, acmeOwnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(asBusiness(delete("/clients/" + acmeClient.getId()), otherBusiness, acmeOwnerId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deleting a client with invoices is refused with a 409 pointing at archiving")
    void deleteWithInvoicesConflicts() throws Exception {
        Client client = clientRepository.save(new Client(acme, "Bright Cafe"));
        User owner = userRepository.findById(acmeOwnerId).orElseThrow();
        LocalDate issued = LocalDate.of(2026, 3, 1);
        invoiceRepository.save(
                new Invoice(acme, client, owner, "INV-2026-0001", issued, issued.plusDays(30)));

        mockMvc.perform(asBusiness(delete("/clients/" + client.getId()), acme, acmeOwnerId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/client-has-invoices"))
                .andExpect(jsonPath("$.detail").value(containsString("Archive")));
    }

    @Test
    @DisplayName("deleting a client with no invoices succeeds")
    void deleteWithoutInvoicesSucceeds() throws Exception {
        Client client = clientRepository.save(new Client(acme, "Bright Cafe"));

        mockMvc.perform(asBusiness(delete("/clients/" + client.getId()), acme, acmeOwnerId))
                .andExpect(status().isNoContent());
        mockMvc.perform(asBusiness(get("/clients/" + client.getId()), acme, acmeOwnerId))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("archiving a client through PUT removes it from the default list")
    void archivedClientsAreExcludedFromTheDefaultList() throws Exception {
        Client keep = clientRepository.save(new Client(acme, "Bright Cafe"));
        Client toArchive = clientRepository.save(new Client(acme, "Old Client"));

        String archiveBody = objectMapper.writeValueAsString(
                new ClientRequest("Old Client", null, null, null, null, null, null, true));
        mockMvc.perform(asBusiness(put("/clients/" + toArchive.getId()), acme, acmeOwnerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        // Default view: only the un-archived client shows up.
        mockMvc.perform(asBusiness(get("/clients"), acme, acmeOwnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value(keep.getName()));

        // Explicitly asking for archived clients finds the one just archived.
        mockMvc.perform(asBusiness(get("/clients").queryParam("archived", "true"), acme, acmeOwnerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value(toArchive.getName()));
    }

    /** Attaches the development identity headers {@link CurrentRequest} reads. */
    private MockHttpServletRequestBuilder asBusiness(
            MockHttpServletRequestBuilder request, Business business, Long userId) {
        return request
                .header(CurrentRequest.BUSINESS_HEADER, business.getId())
                .header(CurrentRequest.USER_HEADER, userId);
    }
}
