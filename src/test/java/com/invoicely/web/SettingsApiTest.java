package com.invoicely.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invoicely.TestTokens;
import com.invoicely.TestcontainersConfiguration;
import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.JwtService;
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
 * Business and GST settings.
 *
 * <p>These endpoints exist because without them the GST feature is unreachable:
 * registration creates every business as not GST-registered, and nothing else
 * could ever change that. The effect of the rate on already-sent invoices is
 * proved in {@code JourneyTest} — here the concern is who may change it and
 * what values are accepted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class SettingsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    private User owner;
    private User staff;

    @BeforeEach
    void seedABusinessWithBothRoles() {
        Business acme = businesses.save(new Business("Acme Renovations"));
        owner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        staff = users.save(new User(acme, "Sam Staff", uniqueEmail(), "hash", Role.STAFF));
    }

    @Test
    @DisplayName("a new business starts unregistered for GST on 30-day terms")
    void defaultsAreWhatRegistrationLeftBehind() throws Exception {
        mockMvc.perform(get("/settings").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Renovations"))
                .andExpect(jsonPath("$.gstRegistered").value(false))
                .andExpect(jsonPath("$.defaultPaymentTermsDays").value(30));
    }

    @Test
    @DisplayName("the owner can register for GST and set the rate")
    void theOwnerCanTurnGstOn() throws Exception {
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Acme Renovations", true, "0.09", 14)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRegistered").value(true))
                .andExpect(jsonPath("$.gstRate").value(0.0900))
                .andExpect(jsonPath("$.defaultPaymentTermsDays").value(14));
    }

    @Test
    @DisplayName("deregistering keeps the rate, so turning it back on does not lose it")
    void deregisteringRemembersTheRate() throws Exception {
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Acme Renovations", false, "0.09", 30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRegistered").value(false))
                .andExpect(jsonPath("$.gstRate").value(0.0900));
    }

    @Test
    @DisplayName("staff cannot read or change what the business charges")
    void staffAreLockedOut() throws Exception {
        mockMvc.perform(get("/settings").headers(as(staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/insufficient-role"));

        mockMvc.perform(put("/settings").headers(as(staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Sneaky Renovations", true, "0.00", 30)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("payment terms outside 7, 14 and 30 are refused with an explanation")
    void unsupportedTermsAreRejected() throws Exception {
        // The V1 migration has a CHECK constraint saying the same thing. This
        // is here so the answer is a readable 400 rather than the 500 a raw
        // constraint violation would produce.
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Acme Renovations", false, "0.09", 21)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("/problems/unsupported-payment-terms"));
    }

    @Test
    @DisplayName("a GST rate of 100% or more is refused")
    void anImpossibleRateIsRejected() throws Exception {
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Acme Renovations", true, "1.50", 30)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'gstRate')]").exists());
    }

    private String settings(String name, boolean gstRegistered, String rate, int terms) {
        return """
                {
                  "name": "%s",
                  "gstRegistered": %s,
                  "gstRate": %s,
                  "defaultPaymentTermsDays": %d
                }
                """.formatted(name, gstRegistered, rate, terms);
    }

    private HttpHeaders as(User user) {
        return TestTokens.bearer(jwtService, user);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
