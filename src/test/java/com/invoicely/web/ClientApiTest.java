package com.invoicely.web;

import static org.hamcrest.Matchers.containsString;
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
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.JwtService;
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
 * against a real PostgreSQL (Testcontainers). Task 4 replaced the permit-all
 * security stub with real authentication, so identity now comes from a real
 * {@code Authorization: Bearer} token, signed by the application's own
 * {@link JwtService} via {@link TestTokens#bearer}, which {@link
 * CurrentRequest} reads the {@code sub}/{@code biz} claims off.
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

    @Autowired
    private JwtService jwtService;

    private Business acme;
    private Business otherBusiness;
    private User acmeOwner;
    private User otherOwner;

    @BeforeEach
    void seedTwoBusinesses() {
        acme = businessRepository.save(new Business("Acme Renovations"));
        acmeOwner = userRepository.save(new User(acme, "Ada Owner", "ada@acme.example", "hash", Role.OWNER));

        // A second business exists purely to prove cross-business isolation:
        // it never appears in the "happy path" assertions. It needs its own
        // owner because a token always carries its own user's business — a
        // caller can no longer be handed one business's id alongside another
        // business's user, the way the old development headers allowed.
        otherBusiness = businessRepository.save(new Business("Other Contractors"));
        otherOwner = userRepository.save(new User(otherBusiness, "Ben Owner", "ben@other.example", "hash", Role.OWNER));
    }

    @Test
    @DisplayName("a created client round-trips through GET with everything it was given")
    void createThenReadRoundTrip() throws Exception {
        String body = objectMapper.writeValueAsString(new ClientRequest(
                "Bright Cafe", "Bao Lin", "bao@brightcafe.example", "+65 9123 4567",
                "1 Cafe Street, Singapore", "T09LL1234A", "PayNow to +65 9123 4567", false));

        String location = mockMvc.perform(asBusiness(post("/clients"), acmeOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Bright Cafe"))
                .andExpect(jsonPath("$.archived").value(false))
                .andReturn()
                .getResponse()
                .getHeader("Location");

        mockMvc.perform(asBusiness(get(location), acmeOwner))
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

        mockMvc.perform(asBusiness(post("/clients"), acmeOwner)
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

        mockMvc.perform(asBusiness(get("/clients/" + acmeClient.getId()), otherOwner))
                .andExpect(status().isNotFound());
        mockMvc.perform(asBusiness(put("/clients/" + acmeClient.getId()), otherOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
        mockMvc.perform(asBusiness(delete("/clients/" + acmeClient.getId()), otherOwner))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deleting a client with invoices is refused with a 409 pointing at archiving")
    void deleteWithInvoicesConflicts() throws Exception {
        Client client = clientRepository.save(new Client(acme, "Bright Cafe"));
        LocalDate issued = LocalDate.of(2026, 3, 1);
        invoiceRepository.save(
                new Invoice(acme, client, acmeOwner, "INV-2026-0001", issued, issued.plusDays(30)));

        mockMvc.perform(asBusiness(delete("/clients/" + client.getId()), acmeOwner))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/client-has-invoices"))
                .andExpect(jsonPath("$.detail").value(containsString("Archive")));
    }

    @Test
    @DisplayName("deleting a client with no invoices succeeds")
    void deleteWithoutInvoicesSucceeds() throws Exception {
        Client client = clientRepository.save(new Client(acme, "Bright Cafe"));

        mockMvc.perform(asBusiness(delete("/clients/" + client.getId()), acmeOwner))
                .andExpect(status().isNoContent());
        mockMvc.perform(asBusiness(get("/clients/" + client.getId()), acmeOwner))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("archiving a client through PUT removes it from the default list")
    void archivedClientsAreExcludedFromTheDefaultList() throws Exception {
        Client keep = clientRepository.save(new Client(acme, "Bright Cafe"));
        Client toArchive = clientRepository.save(new Client(acme, "Old Client"));

        String archiveBody = objectMapper.writeValueAsString(
                new ClientRequest("Old Client", null, null, null, null, null, null, true));
        mockMvc.perform(asBusiness(put("/clients/" + toArchive.getId()), acmeOwner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archiveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));

        // Default view: only the un-archived client shows up.
        mockMvc.perform(asBusiness(get("/clients"), acmeOwner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value(keep.getName()));

        // Explicitly asking for archived clients finds the one just archived.
        mockMvc.perform(asBusiness(get("/clients").queryParam("archived", "true"), acmeOwner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].name").value(toArchive.getName()));
    }

    /** Attaches the bearer token {@link CurrentRequest} reads the caller's identity from. */
    private MockHttpServletRequestBuilder asBusiness(MockHttpServletRequestBuilder request, User user) {
        return request.headers(TestTokens.bearer(jwtService, user));
    }
}
