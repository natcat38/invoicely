package com.invoicely;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One business, from nothing to a paid invoice, over real HTTP.
 *
 * <p>Everything here goes through the API. No repository is injected and no
 * entity is touched: the only way this test can reach a state is the way a
 * browser would. That is the point — the other test classes each prove one
 * rule in isolation, and this one proves the rules compose.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> Every other API test wraps
 * itself in a transaction that rolls back, which is fast and isolated but means
 * nothing is ever really committed and one persistence context spans the whole
 * test. This one commits between steps, exactly as production does — which is
 * how it would catch a change that only works because a transaction was still
 * open. It pays for that by leaving rows behind in the throwaway container, and
 * by using a fresh email per run so it never collides with itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JourneyTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("register, hire, draft, submit, reject, resubmit, approve, send, pay, done")
    void theWholeThing() throws Exception {
        // 1. Registering creates the business and its owner, and logs them in.
        String ownerToken = tokenFrom(perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "businessName": "Acme Renovations",
                          "ownerName": "Ada Owner",
                          "email": "%s",
                          "password": "a-long-enough-password"
                        }
                        """.formatted(uniqueEmail()))
                , status().isCreated()));

        // 2. The owner turns on GST. Without this endpoint the whole GST
        //    feature would be unreachable: every business registers as not
        //    GST-registered and nothing else could change it.
        perform(put("/settings").headers(bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "Acme Renovations",
                          "gstRegistered": true,
                          "gstRate": 0.09,
                          "defaultPaymentTermsDays": 30
                        }
                        """), status().isOk())
                .andExpect(jsonPath("$.gstRegistered").value(true));

        // 3. The owner hires a staff member and is handed a temporary password
        //    once, in this response and nowhere else.
        String staffEmail = uniqueEmail();
        MvcResult hired = perform(post("/team").headers(bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Sam Staff", "email": "%s"}
                        """.formatted(staffEmail)), status().isCreated())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andReturn();
        String temporaryPassword = JsonPath.read(body(hired), "$.temporaryPassword");
        assertThat(temporaryPassword).isNotBlank();

        // 4. Their first login works, but tells them they are not done.
        MvcResult firstLogin = perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(staffEmail, temporaryPassword)), status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andReturn();
        String temporaryToken = JsonPath.read(body(firstLogin), "$.token");

        // 5. And that token opens nothing else until the password is replaced.
        perform(get("/clients").headers(bearer(temporaryToken)), status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));

        String staffToken = tokenFrom(perform(post("/auth/change-password")
                .headers(bearer(temporaryToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword": "%s", "newPassword": "sam-picks-this-one"}
                        """.formatted(temporaryPassword)), status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false)));

        // 6. Now they can work: a client, then a draft invoice for 1,420.00.
        MvcResult client = perform(post("/clients").headers(bearer(staffToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Bright Cafe", "uen": "201812345K"}
                        """), status().isCreated()).andReturn();
        int clientId = JsonPath.read(body(client), "$.id");

        LocalDate today = LocalDate.now();
        MvcResult drafted = perform(post("/invoices").headers(bearer(staffToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "clientId": %d,
                          "issueDate": "%s",
                          "lineItems": [
                            {"description": "Design", "quantity": "2", "unitPrice": "400.00"},
                            {"description": "Build",  "quantity": "1", "unitPrice": "620.00"}
                          ]
                        }
                        """.formatted(clientId, today)), status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.subtotal").value(1420.00))
                // GST is live from the business setting while still a draft.
                .andExpect(jsonPath("$.total").value(1547.80))
                .andReturn();
        int invoiceId = JsonPath.read(body(drafted), "$.id");

        // 7. Staff submit for approval, and discover what they may not do.
        perform(post("/invoices/" + invoiceId + "/submit").headers(bearer(staffToken)),
                status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));

        perform(post("/invoices/" + invoiceId + "/send").headers(bearer(staffToken)),
                status().isForbidden());
        perform(get("/dashboard").headers(bearer(staffToken)), status().isForbidden());
        perform(get("/settings").headers(bearer(staffToken)), status().isForbidden());

        // 8. The owner sends it back with a reason, and the staff member fixes
        //    it and resubmits. The note must not follow it forward.
        perform(post("/invoices/" + invoiceId + "/reject").headers(bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"note": "Bright Cafe agreed 380 a day, not 400."}
                        """), status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rejectionNote").exists());

        perform(post("/invoices/" + invoiceId + "/submit").headers(bearer(staffToken)),
                status().isOk())
                .andExpect(jsonPath("$.rejectionNote").doesNotExist());

        // 9. The owner sees it waiting, then approves and sends it.
        perform(get("/dashboard").headers(bearer(ownerToken)), status().isOk())
                .andExpect(jsonPath("$.awaitingApprovalCount").value(1))
                .andExpect(jsonPath("$.awaitingApprovalQueue[0].id").value(invoiceId));

        perform(post("/invoices/" + invoiceId + "/send").headers(bearer(ownerToken)),
                status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.gstRate").value(0.0900));

        // 10. Changing the GST rate now cannot touch an invoice already sent.
        perform(put("/settings").headers(bearer(ownerToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "name": "Acme Renovations",
                          "gstRegistered": true,
                          "gstRate": 0.11,
                          "defaultPaymentTermsDays": 30
                        }
                        """), status().isOk());
        perform(get("/invoices/" + invoiceId).headers(bearer(ownerToken)), status().isOk())
                .andExpect(jsonPath("$.gstRate").value(0.0900))
                .andExpect(jsonPath("$.total").value(1547.80));

        // 11. Money. Staff may not touch it; the owner may, but not for more
        //     than is owed.
        perform(payment(staffToken, invoiceId, "100.00", today), status().isForbidden());

        perform(payment(ownerToken, invoiceId, "9999.00", today), status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("Amount exceeds the remaining balance (S$1,547.80)."));

        perform(payment(ownerToken, invoiceId, "500.00", today), status().isCreated());
        perform(get("/invoices/" + invoiceId).headers(bearer(ownerToken)), status().isOk())
                .andExpect(jsonPath("$.balance").value(1047.80))
                .andExpect(jsonPath("$.status").value("SENT"));

        // 12. Paying the rest, to the cent, closes it.
        perform(payment(ownerToken, invoiceId, "1047.80", today), status().isCreated());
        perform(get("/invoices/" + invoiceId).headers(bearer(ownerToken)), status().isOk())
                .andExpect(jsonPath("$.balance").value(0.00))
                .andExpect(jsonPath("$.status").value("PAID"));

        // 13. And the dashboard agrees: nothing outstanding, the money in.
        perform(get("/dashboard").headers(bearer(ownerToken)), status().isOk())
                .andExpect(jsonPath("$.outstandingTotal").value(0.00))
                .andExpect(jsonPath("$.awaitingApprovalCount").value(0))
                .andExpect(jsonPath("$.revenueThisMonth").value(1547.80));
    }

    @Test
    @DisplayName("a second business cannot see or touch the first one's records")
    void businessesAreInvisibleToEachOther() throws Exception {
        String firstToken = registerABusiness("First Contractors");
        String secondToken = registerABusiness("Second Contractors");

        MvcResult client = perform(post("/clients").headers(bearer(firstToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Only Mine"}
                        """), status().isCreated()).andReturn();
        int clientId = JsonPath.read(body(client), "$.id");

        MvcResult invoice = perform(post("/invoices").headers(bearer(firstToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "clientId": %d,
                          "lineItems": [{"description": "Work", "quantity": "1", "unitPrice": "10.00"}]
                        }
                        """.formatted(clientId)), status().isCreated()).andReturn();
        int invoiceId = JsonPath.read(body(invoice), "$.id");

        // 404 throughout, never 403: a 403 would confirm the row exists, which
        // is the whole point of ADR-0001.
        perform(get("/invoices/" + invoiceId).headers(bearer(secondToken)), status().isNotFound());
        perform(get("/clients/" + clientId).headers(bearer(secondToken)), status().isNotFound());
        perform(post("/invoices/" + invoiceId + "/send").headers(bearer(secondToken)),
                status().isNotFound());
        perform(payment(secondToken, invoiceId, "1.00", LocalDate.now()), status().isNotFound());

        // And nothing of the first business leaks into the second's own views.
        perform(get("/invoices").headers(bearer(secondToken)), status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        perform(get("/dashboard").headers(bearer(secondToken)), status().isOk())
                .andExpect(jsonPath("$.outstandingTotal").value(0.00));
    }

    @Test
    @DisplayName("the OpenAPI document is served, and readable without a token")
    void theApiDocumentsItself() throws Exception {
        perform(get("/v3/api-docs"), status().isOk())
                .andExpect(jsonPath("$.info.title").value("Invoicely API"))
                .andExpect(jsonPath("$.paths['/invoices/{id}/send']").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer-jwt").exists());
    }

    private String registerABusiness(String name) throws Exception {
        return tokenFrom(perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "businessName": "%s",
                          "ownerName": "An Owner",
                          "email": "%s",
                          "password": "a-long-enough-password"
                        }
                        """.formatted(name, uniqueEmail())), status().isCreated()));
    }

    private MockHttpServletRequestBuilder payment(String token, int invoiceId, String amount,
                                                  LocalDate paidAt) {
        return post("/invoices/" + invoiceId + "/payments").headers(bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount": "%s", "paidAt": "%s", "method": "BANK_TRANSFER"}
                        """.formatted(amount, paidAt));
    }

    private org.springframework.test.web.servlet.ResultActions perform(
            MockHttpServletRequestBuilder request,
            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        var actions = mockMvc.perform(request);
        try {
            return actions.andExpect(expected);
        } catch (AssertionError failed) {
            // A journey is a long chain of requests, and "expected 201 but was
            // 400" fifteen steps in says nothing about which field upset the
            // server. The body usually says exactly that.
            System.out.println("Journey step failed. Response body was: "
                    + actions.andReturn().getResponse().getContentAsString());
            throw failed;
        }
    }

    private String tokenFrom(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return JsonPath.read(body(actions.andReturn()), "$.token");
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** Emails are globally unique, and this test commits — so it needs new ones. */
    private String uniqueEmail() {
        return "journey-" + UUID.randomUUID() + "@example.test";
    }
}
