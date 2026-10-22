package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invoicely.TestcontainersConfiguration;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves a visitor holding the public demo password cannot lock everyone else
 * out (see {@link DemoAccountGuard}), and that ordinary accounts running next
 * to the demo are unaffected.
 *
 * <p>Runs under the {@code demo} profile, so {@code DemoDataSeeder} has
 * already seeded the two demo logins by the time a test method runs. Not
 * {@code @Transactional}: the seed commits at startup, and the requests below
 * must see exactly what a real client would. Every assertion that something
 * did <em>not</em> change finishes by signing in again with the original
 * password, because "the API said 403" is not the same as "the account still
 * works".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("demo")
@TestPropertySource(properties = "invoicely.demo.password=a-password-for-this-test-run")
class DemoAccountProtectionApiTest {

    private static final String OWNER_EMAIL = "owner@invoicely.demo";
    private static final String STAFF_EMAIL = "staff@invoicely.demo";
    private static final String DEMO_PASSWORD = "a-password-for-this-test-run";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository users;

    @Test
    @DisplayName("the demo owner's password cannot be changed, even with the correct current password")
    void demoOwnerPasswordCannotBeChanged() throws Exception {
        HttpHeaders owner = signIn(OWNER_EMAIL, DEMO_PASSWORD);

        mockMvc.perform(post("/auth/change-password").headers(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(DEMO_PASSWORD, "a-lockout-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/demo-account-protected"))
                .andExpect(jsonPath("$.detail").value(containsString("shared demo account")));

        // The demo password still works and the attempted one does not.
        signIn(OWNER_EMAIL, DEMO_PASSWORD);
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(OWNER_EMAIL, "a-lockout-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the demo staff password cannot be changed either")
    void demoStaffPasswordCannotBeChanged() throws Exception {
        HttpHeaders staff = signIn(STAFF_EMAIL, DEMO_PASSWORD);

        mockMvc.perform(post("/auth/change-password").headers(staff)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody(DEMO_PASSWORD, "a-lockout-password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/demo-account-protected"));

        signIn(STAFF_EMAIL, DEMO_PASSWORD);
    }

    @Test
    @DisplayName("the owner cannot deactivate the demo staff login")
    void demoStaffCannotBeDeactivated() throws Exception {
        HttpHeaders owner = signIn(OWNER_EMAIL, DEMO_PASSWORD);
        User demoStaff = users.findByEmailIgnoreCase(STAFF_EMAIL).orElseThrow();

        mockMvc.perform(patch("/team/" + demoStaff.getId()).headers(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("/problems/demo-account-protected"));

        assertThat(users.findById(demoStaff.getId()).orElseThrow().isActive()).isTrue();
        signIn(STAFF_EMAIL, DEMO_PASSWORD);
    }

    @Test
    @DisplayName("reactivating a demo account is still allowed: it can only restore access")
    void demoStaffCanBeReactivated() throws Exception {
        HttpHeaders owner = signIn(OWNER_EMAIL, DEMO_PASSWORD);
        User demoStaff = users.findByEmailIgnoreCase(STAFF_EMAIL).orElseThrow();

        mockMvc.perform(patch("/team/" + demoStaff.getId()).headers(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a business registered next to the demo can still change passwords and deactivate staff")
    void ordinaryAccountsAreUnaffected() throws Exception {
        String email = "owner-" + UUID.randomUUID() + "@example.com";
        String registered = mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "businessName": "Ordinary Pte Ltd",
                                  "ownerName": "Olivia Ordinary",
                                  "email": "%s",
                                  "password": "an-ordinary-password"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        HttpHeaders owner = new HttpHeaders();
        owner.setBearerAuth(objectMapper.readValue(registered, AuthResponse.class).token());

        mockMvc.perform(post("/auth/change-password").headers(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changePasswordBody("an-ordinary-password", "a-newer-ordinary-password")))
                .andExpect(status().isOk());
        owner = signIn(email, "a-newer-ordinary-password");

        String staffEmail = "staff-" + UUID.randomUUID() + "@example.com";
        String created = mockMvc.perform(post("/team").headers(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Sam Staff\", \"email\": \"%s\"}".formatted(staffEmail)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long staffId = objectMapper.readValue(created, CreatedStaffResponse.class).id();

        mockMvc.perform(patch("/team/" + staffId).headers(owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    private HttpHeaders signIn(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(objectMapper.readValue(body, AuthResponse.class).token());
        return headers;
    }

    private static String loginBody(String email, String password) {
        return "{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password);
    }

    private static String changePasswordBody(String current, String next) {
        return "{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(current, next);
    }
}
