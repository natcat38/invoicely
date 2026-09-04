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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code /team} endpoints, end to end: real HTTP handling, real
 * PostgreSQL, real Flyway schema, real signed JWTs via {@link TestTokens}.
 *
 * <p>Users are seeded straight through {@link UserRepository} rather than via
 * {@code POST /auth/register} — that endpoint belongs to a different vertical
 * slice of Task 4 and may not exist yet while this test is being written. The
 * two meet only at {@link JwtService}, which both sides already depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class TeamApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Business acme;
    private User owner;
    private User staff;

    @BeforeEach
    void seedABusiness() {
        acme = businesses.save(new Business("Acme Renovations"));
        owner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        staff = users.save(new User(acme, "Sam Staff", uniqueEmail(), "hash", Role.STAFF));
    }

    @Test
    @DisplayName("the owner lists everyone in the business, alphabetical")
    void ownerListsTheTeam() throws Exception {
        mockMvc.perform(get("/team").headers(ownerAuth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // Ada Owner sorts before Sam Staff.
                .andExpect(jsonPath("$[0].name").value("Ada Owner"))
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[1].name").value("Sam Staff"))
                .andExpect(jsonPath("$[1].role").value("STAFF"))
                .andExpect(jsonPath("$[1].active").value(true));
    }

    @Test
    @DisplayName("the owner creates a staff account and gets a working temporary password back once")
    void ownerCreatesStaff() throws Exception {
        String email = uniqueEmail();

        String body = mockMvc.perform(post("/team").headers(ownerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New Hire", "email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("New Hire"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // Non-blank and at least the 8-character minimum change-password will
        // later enforce — a credential shape check, not a full login attempt.
        String temporaryPassword = extractTemporaryPassword(body);
        assertThat(temporaryPassword).isNotBlank();
        assertThat(temporaryPassword.length()).isGreaterThanOrEqualTo(8);

        // must_change_password is not on the response (it is not the Team
        // page's business) — check it where it actually lives.
        User created = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(created.isMustChangePassword()).isTrue();
        assertThat(created.getRole()).isEqualTo(Role.STAFF);
        assertThat(created.getPasswordHash()).isNotEqualTo(temporaryPassword);
    }

    @Test
    @DisplayName("the temporary password is hashed for storage, never kept in plain text")
    void temporaryPasswordIsHashedNotStored() throws Exception {
        String email = uniqueEmail();

        String body = mockMvc.perform(post("/team").headers(ownerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "New Hire", "email": "%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String temporaryPassword = extractTemporaryPassword(body);
        User created = users.findByEmailIgnoreCase(email).orElseThrow();

        assertThat(created.getPasswordHash()).isNotEqualTo(temporaryPassword);
        assertThat(passwordEncoder.matches(temporaryPassword, created.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("a duplicate email is rejected with a 409, not a second account")
    void duplicateEmailIsRejected() throws Exception {
        mockMvc.perform(post("/team").headers(ownerAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Copycat", "email": "%s"}
                                """.formatted(staff.getEmail())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/email-taken"));
    }

    @Test
    @DisplayName("the owner deactivates another user, and can reactivate them again")
    void ownerDeactivatesAndReactivatesStaff() throws Exception {
        mockMvc.perform(patch("/team/" + staff.getId()).headers(ownerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(users.findById(staff.getId()).orElseThrow().isActive()).isFalse();

        mockMvc.perform(patch("/team/" + staff.getId()).headers(ownerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("the owner cannot deactivate themselves, so the business is never left without one")
    void ownerCannotDeactivateSelf() throws Exception {
        mockMvc.perform(patch("/team/" + owner.getId()).headers(ownerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("/problems/cannot-deactivate-self"));
        assertThat(users.findById(owner.getId()).orElseThrow().isActive()).isTrue();
    }

    @Test
    @DisplayName("a user id from another business is invisible, not forbidden")
    void patchOnAnotherBusinessesUserIs404() throws Exception {
        Business other = businesses.save(new Business("Other Contractors"));
        User outsider = users.save(new User(other, "Ben Owner", uniqueEmail(), "hash", Role.OWNER));

        mockMvc.perform(patch("/team/" + outsider.getId()).headers(ownerAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("/problems/not-found"));
    }

    @Test
    @DisplayName("a staff token is forbidden on every /team endpoint")
    void staffIsForbiddenOnEveryEndpoint() throws Exception {
        mockMvc.perform(get("/team").headers(staffAuth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/team").headers(staffAuth()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Nope", "email": "%s"}
                                """.formatted(uniqueEmail())))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/team/" + owner.getId()).headers(staffAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false}
                                """))
                .andExpect(status().isForbidden());
    }

    private HttpHeaders ownerAuth() {
        return TestTokens.bearer(jwtService, owner);
    }

    private HttpHeaders staffAuth() {
        return TestTokens.bearer(jwtService, staff);
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }

    /** Pulls {@code temporaryPassword} out of the create response body without a full JSON parser. */
    private String extractTemporaryPassword(String responseBody) {
        String marker = "\"temporaryPassword\":\"";
        int start = responseBody.indexOf(marker) + marker.length();
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }
}
