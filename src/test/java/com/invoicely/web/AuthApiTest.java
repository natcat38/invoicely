package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The {@code /auth} endpoints, end to end: real HTTP handling, real
 * PostgreSQL, real Flyway schema, real password hashing and real JWTs — the
 * same {@link JwtService} and {@link PasswordEncoder} beans the application
 * uses, not mocks of them, because the entire point of this endpoint is that
 * those two things work together correctly.
 *
 * <p>Request bodies are written as JSON text blocks rather than built from
 * objects, the same convention {@code InvoiceApiTest} uses: the test then
 * asserts against the wire format an actual client would send.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Test
    @DisplayName("registering creates the business and its owner, and the token it returns actually authenticates")
    void registerCreatesTheBusinessAndOwnerWithAWorkingToken() throws Exception {
        String email = uniqueEmail();

        MvcResult result = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessName": "Acme Renovations",
                                  "ownerName": "Ada Owner",
                                  "email": "%s",
                                  "password": "a-fine-password"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Ada Owner"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.businessName").value("Acme Renovations"))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                .andExpect(jsonPath("$.token").exists())
                .andReturn();

        // The response fields prove the shape is right; this proves the token
        // in it is not just well-formed JSON but an actual working credential.
        AuthResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), AuthResponse.class);
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(response.token());
        mockMvc.perform(get("/clients").headers(bearer))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("registering with an email already in use is rejected rather than merged")
    void registerRejectsADuplicateEmail() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("First Co", email)));

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("Second Co", email)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/email-taken"));
    }

    @Test
    @DisplayName("login succeeds with the right password and fails identically for an unknown email or a wrong one")
    void loginFailsIdenticallyWhetherTheEmailIsUnknownOrThePasswordIsWrong() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        String email = uniqueEmail();
        users.save(new User(business, "Ada Owner", email, passwordEncoder.encode("correct-password"), Role.OWNER));

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "correct-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());

        String wrongPasswordBody = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/invalid-credentials"))
                .andReturn().getResponse().getContentAsString();

        String unknownEmailBody = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(uniqueEmail(), "whatever-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/invalid-credentials"))
                .andReturn().getResponse().getContentAsString();

        // Not just the same status and type: byte-for-byte the same body, so
        // nothing in it could tell an attacker which email addresses exist.
        assertThat(wrongPasswordBody).isEqualTo(unknownEmailBody);
    }

    @Test
    @DisplayName("a deactivated user cannot log in, even with the correct password")
    void deactivatedUserCannotLogIn() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        String email = uniqueEmail();
        User staff = new User(business, "Retired Staff", email,
                passwordEncoder.encode("correct-password"), Role.STAFF);
        staff.setActive(false);
        users.save(staff);

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, "correct-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/invalid-credentials"));
    }

    @Test
    @DisplayName("changing the password works and clears must-change-password")
    void changePasswordWorksAndClearsTheFlag() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User staff = new User(business, "New Staff", uniqueEmail(),
                passwordEncoder.encode("temporary-password"), Role.STAFF);
        staff.setMustChangePassword(true);
        staff = users.save(staff);

        mockMvc.perform(post("/auth/change-password").headers(TestTokens.bearer(jwtService, staff))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "temporary-password",
                                  "newPassword": "a-permanent-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mustChangePassword").value(false));

        User reloaded = users.findById(staff.getId()).orElseThrow();
        assertThat(reloaded.isMustChangePassword()).isFalse();
        assertThat(passwordEncoder.matches("a-permanent-password", reloaded.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("changing the password with the wrong current password is rejected")
    void changePasswordRejectsTheWrongCurrentPassword() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User owner = users.save(new User(business, "Ada Owner", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.OWNER));

        mockMvc.perform(post("/auth/change-password").headers(TestTokens.bearer(jwtService, owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "not-the-password",
                                  "newPassword": "a-permanent-password"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/invalid-credentials"));
    }

    @Test
    @DisplayName("a user who must change their password is blocked everywhere except change-password itself")
    void mustChangePasswordBlocksEverythingExceptChangePassword() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User staff = new User(business, "New Staff", uniqueEmail(),
                passwordEncoder.encode("temporary-password"), Role.STAFF);
        staff.setMustChangePassword(true);
        staff = users.save(staff);
        HttpHeaders bearer = TestTokens.bearer(jwtService, staff);

        // This proves AccountStateFilter's carve-out, not anything AuthService
        // itself does — the filter already exists and is not owned by this slice.
        mockMvc.perform(get("/clients").headers(bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));

        mockMvc.perform(post("/auth/change-password").headers(bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "temporary-password",
                                  "newPassword": "a-permanent-password"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a request with no token at all is rejected")
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/clients"))
                .andExpect(status().isUnauthorized());
    }

    private String registerBody(String businessName, String email) {
        return """
                {
                  "businessName": "%s",
                  "ownerName": "Owner Name",
                  "email": "%s",
                  "password": "a-fine-password"
                }
                """.formatted(businessName, email);
    }

    private String loginBody(String email, String password) {
        return """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
