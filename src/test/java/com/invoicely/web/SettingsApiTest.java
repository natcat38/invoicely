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
    @DisplayName("settings are scoped by the caller's own token, so one business never sees another's GST configuration")
    void settingsNeverLeakAcrossBusinesses() throws Exception {
        // /settings takes no id — it is scoped entirely by the business claim
        // on the caller's token, unlike invoices/clients/payments, which are
        // proven isolated via an explicit findByIdAndBusinessId 404. That
        // makes this an unverified structural assumption rather than a
        // guarded lookup, worth asserting as its own property.
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Acme Renovations", true, "0.09", 14)))
                .andExpect(status().isOk());

        Business other = businesses.save(new Business("Other Contractors"));
        User otherOwner = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));
        mockMvc.perform(put("/settings").headers(as(otherOwner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Other Contractors", false, "0.00", 30)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/settings").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRegistered").value(true))
                .andExpect(jsonPath("$.gstRate").value(0.0900))
                .andExpect(jsonPath("$.defaultPaymentTermsDays").value(14));

        mockMvc.perform(get("/settings").headers(as(otherOwner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstRegistered").value(false))
                .andExpect(jsonPath("$.defaultPaymentTermsDays").value(30));
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

    @Test
    @DisplayName("the owner can set the invoice letterhead's address and UEN, and read them back")
    void addressAndUenRoundTripThroughSettings() throws Exception {
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsWithLetterhead("Acme Renovations", false, "0.09", 30,
                                "1 Renovation Row, Singapore 654321", "201234567A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("1 Renovation Row, Singapore 654321"))
                .andExpect(jsonPath("$.uen").value("201234567A"));

        // Reading it back separately proves the values were actually persisted,
        // not just echoed straight from the request body.
        mockMvc.perform(get("/settings").headers(as(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value("1 Renovation Row, Singapore 654321"))
                .andExpect(jsonPath("$.uen").value("201234567A"));
    }

    @Test
    @DisplayName("omitting the address and UEN is accepted, not a 400, and leaves them null")
    void omittingTheLetterheadFieldsIsAcceptedAndLeavesThemNull() throws Exception {
        // `settings(...)` sends a body with no address/uen fields at all, unlike
        // gstRegistered/gstRate/defaultPaymentTermsDays which are required. A
        // business may legitimately never fill in a letterhead.
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settings("Acme Renovations", false, "0.09", 30)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.uen").doesNotExist());
    }

    @Test
    @DisplayName("an address over 500 characters is refused")
    void anOverlongAddressIsRejected() throws Exception {
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsWithLetterhead(
                                "Acme Renovations", false, "0.09", 30, "A".repeat(501), null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'address')]").exists());
    }

    @Test
    @DisplayName("a UEN over 20 characters is refused")
    void anOverlongUenIsRejected() throws Exception {
        mockMvc.perform(put("/settings").headers(as(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(settingsWithLetterhead(
                                "Acme Renovations", false, "0.09", 30, null, "1".repeat(21))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'uen')]").exists());
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

    private String settingsWithLetterhead(
            String name, boolean gstRegistered, String rate, int terms, String address, String uen) {
        return """
                {
                  "name": "%s",
                  "gstRegistered": %s,
                  "gstRate": %s,
                  "defaultPaymentTermsDays": %d,
                  "address": %s,
                  "uen": %s
                }
                """.formatted(name, gstRegistered, rate, terms, asJsonString(address), asJsonString(uen));
    }

    /** Renders a possibly-null Java string as a JSON string literal or bare {@code null}. */
    private String asJsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private HttpHeaders as(User user) {
        return TestTokens.bearer(jwtService, user);
    }

    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
