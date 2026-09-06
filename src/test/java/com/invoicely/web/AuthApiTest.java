package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.time.Instant;
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
    @DisplayName("a token issued before deactivation is rejected on the very next request, not just at a fresh login")
    void tokenIssuedBeforeDeactivationIsRejectedOnReplay() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User staff = users.save(new User(business, "Sam Staff", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.STAFF));
        HttpHeaders bearer = TestTokens.bearer(jwtService, staff);

        // The token is minted while the account is still active — proven by
        // using it successfully once before deactivating.
        mockMvc.perform(get("/clients").headers(bearer))
                .andExpect(status().isOk());

        staff.setActive(false);
        users.saveAndFlush(staff);

        // Same token, replayed after deactivation. deactivatedUserCannotLogIn
        // above proves a fresh login is refused, but that is AuthService, a
        // different code path. This proves the more dangerous one:
        // AccountStateFilter re-reads isActive() from the database on every
        // request, so a token minted before the change does not keep working
        // until it expires. 403, not 401 (ADR-0010): the token is genuine and
        // names exactly who it claims to, so this is authorisation failing,
        // not authentication.
        mockMvc.perform(get("/clients").headers(bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/account-deactivated"));
    }

    @Test
    @DisplayName("a token minted before password_changed_at moves past it is superseded, and is rejected 401")
    void tokenIssuedBeforePasswordChangeIsRejected() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User owner = users.save(new User(business, "Ada Owner", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.OWNER));
        HttpHeaders oldToken = TestTokens.bearer(jwtService, owner);

        // Proven live before the password "changes" below, so the rejection
        // that follows is caused by that change and not by anything else
        // wrong with the token.
        mockMvc.perform(get("/clients").headers(oldToken)).andExpect(status().isOk());

        // Set directly on the row, a full two seconds ahead, rather than by
        // calling POST /auth/change-password and relying on real elapsed
        // time: AccountStateFilter rejects only a *strictly* older token
        // (ADR-0010's one-second seam), and a token minted moments ago by
        // this same test could easily land in the same wall-clock second as
        // a real change-password call, making that approach to this
        // assertion flaky rather than the code wrong. The real end-to-end
        // path — change-password itself invalidating the token used to call
        // it while its own fresh token keeps working — is proven separately
        // by changingPasswordDoesNotLogOutTheCurrentSession below.
        User reloaded = users.findById(owner.getId()).orElseThrow();
        reloaded.setPasswordChangedAt(Instant.now().plusSeconds(2));
        users.saveAndFlush(reloaded);

        mockMvc.perform(get("/clients").headers(oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("/problems/token-superseded"));
    }

    @Test
    @DisplayName("a token for a user who has never changed their password still works: null is not epoch")
    void tokenWorksWhenPasswordWasNeverChanged() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User owner = users.save(new User(business, "Ada Owner", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.OWNER));
        assertThat(owner.getPasswordChangedAt()).isNull();

        mockMvc.perform(get("/clients").headers(TestTokens.bearer(jwtService, owner)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("changing your password does not log you out of the session you changed it from")
    void changingPasswordDoesNotLogOutTheCurrentSession() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User owner = users.save(new User(business, "Ada Owner", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.OWNER));
        HttpHeaders sessionToken = TestTokens.bearer(jwtService, owner);

        // The real path: AuthService.changePassword stamps password_changed_at
        // and mints a fresh token in the same call, so that fresh token's iat
        // can never be strictly before the stamp it was minted after — this
        // assertion needs no clock trickery to be reliable.
        String body = mockMvc.perform(post("/auth/change-password").headers(sessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "currentPassword": "correct-password",
                                  "newPassword": "a-newer-password"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        AuthResponse response = objectMapper.readValue(body, AuthResponse.class);
        HttpHeaders freshToken = new HttpHeaders();
        freshToken.setBearerAuth(response.token());

        // The fresh token change-password just returned keeps working: the
        // caller lands back in the app, not at a login screen, which is the
        // whole point of stamping password_changed_at and minting the fresh
        // token in the same call (ADR-0010).
        mockMvc.perform(get("/clients").headers(freshToken)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("must-change-password blocks a spread of endpoints, not just the one everyone happens to check")
    void mustChangePasswordBlocksASpreadOfEndpoints() throws Exception {
        // mustChangePasswordBlocksEverythingExceptChangePassword only probes
        // GET /clients. AccountStateFilter's carve-out is implemented
        // generically (every request except the CHANGE_PASSWORD matcher), but
        // that generic implementation is exactly what a narrow test would fail
        // to catch a regression in — e.g. a second carve-out added later for
        // one specific path. An owner is used (rather than staff) so every
        // endpoint below is reachable by role, and the only thing that can be
        // stopping the request is the must-change-password gate.
        Business business = businesses.save(new Business("Acme Renovations"));
        User owner = new User(business, "Ada Owner", uniqueEmail(),
                passwordEncoder.encode("temporary-password"), Role.OWNER);
        owner.setMustChangePassword(true);
        owner = users.save(owner);
        HttpHeaders bearer = TestTokens.bearer(jwtService, owner);

        mockMvc.perform(post("/invoices").headers(bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));

        mockMvc.perform(get("/dashboard").headers(bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));

        mockMvc.perform(get("/team").headers(bearer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));

        mockMvc.perform(patch("/team/999999").headers(bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));

        mockMvc.perform(post("/invoices/999999/payments").headers(bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));
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

    @Test
    @DisplayName("GET /auth/me returns the caller's own identity, with no token in the response")
    void meReturnsTheCallersIdentity() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User owner = users.save(new User(business, "Ada Owner", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.OWNER));

        mockMvc.perform(get("/auth/me").headers(TestTokens.bearer(jwtService, owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner.getId()))
                .andExpect(jsonPath("$.name").value("Ada Owner"))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.businessName").value("Acme Renovations"))
                .andExpect(jsonPath("$.mustChangePassword").value(false))
                // The business's GST setting travels with the identity so the
                // UI can show a correct GST line before an invoice exists —
                // including for staff, who cannot read /settings at all. A
                // freshly created business is not registered, but still
                // carries the default rate, because the rate is kept either
                // way (Business.gstRate).
                .andExpect(jsonPath("$.businessGstRegistered").value(false))
                .andExpect(jsonPath("$.businessGstRate").value(0.0900))
                // MeResponse deliberately has no token field at all — see its
                // Javadoc for why a read endpoint must never be able to hand
                // out a fresh one.
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("a staff member reads the business's GST setting from /auth/me, which they cannot get from /settings")
    void meCarriesTheGstSettingForStaffToo() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        business.setGstRegistered(true);
        business.setGstRate(new java.math.BigDecimal("0.0800"));
        businesses.save(business);

        User staff = users.save(new User(business, "Sam Staff", uniqueEmail(),
                passwordEncoder.encode("correct-password"), Role.STAFF));

        // The point of the test: /settings is owner-only, so before this field
        // existed a staff member had no way to know the business charged GST
        // until an invoice had already been saved — and the builder's live
        // preview showed a document with no GST line at all.
        mockMvc.perform(get("/settings").headers(TestTokens.bearer(jwtService, staff)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/auth/me").headers(TestTokens.bearer(jwtService, staff)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.businessGstRegistered").value(true))
                .andExpect(jsonPath("$.businessGstRate").value(0.0800));
    }

    @Test
    @DisplayName("GET /auth/me with no token at all is rejected")
    void meWithoutATokenIsRejected() throws Exception {
        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /auth/me is blocked for a user who must still change their password, same as everything else")
    void meBlocksAUserWhoMustChangePassword() throws Exception {
        Business business = businesses.save(new Business("Acme Renovations"));
        User staff = new User(business, "New Staff", uniqueEmail(),
                passwordEncoder.encode("temporary-password"), Role.STAFF);
        staff.setMustChangePassword(true);
        staff = users.save(staff);

        mockMvc.perform(get("/auth/me").headers(TestTokens.bearer(jwtService, staff)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/password-change-required"));
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

    @Test
    @DisplayName("a passphrase longer than BCrypt can hash is a 400, not a 500")
    void overlongPasswordsAreRejectedCleanly() throws Exception {
        // BCrypt hashes at most 72 bytes and throws above that, so without a
        // cap this reasonable-looking passphrase would come back as a 500.
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessName": "Long Password Co",
                                  "ownerName": "Ada Owner",
                                  "email": "long@example.test",
                                  "password": "%s"
                                }
                                """.formatted("x".repeat(100))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }
}
