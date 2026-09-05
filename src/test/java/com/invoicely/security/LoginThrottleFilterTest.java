package com.invoicely.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.invoicely.TestcontainersConfiguration;
import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link LoginThrottleFilter}, driven through real HTTP with {@link MockMvc}
 * rather than called directly: the thing under test is the count of real
 * response statuses the filter sees, so a unit test that hand-builds a
 * {@code HttpServletResponse} would have to fake exactly the behaviour this
 * test needs to be honest about.
 *
 * <p>{@code @Transactional} rolls back every row this class writes, the same
 * as {@link com.invoicely.web.AuthApiTest}, but it does nothing at all for
 * {@link LoginThrottleFilter}'s own state: {@code attemptsByIp} lives in a
 * singleton bean's {@code ConcurrentHashMap}, entirely outside the database
 * and outside any transaction, so it survives from one test method to the
 * next in the same Spring context. Every test below therefore uses its own
 * IP address, so one test's failures can never count against another's limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Transactional
class LoginThrottleFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("the 11th failed login from one IP inside the window is rejected with 429 and a Retry-After header")
    void theEleventhFailedLoginFromOneIpIsThrottled() throws Exception {
        String ip = "203.0.113.10";

        // MAX_FAILURES is 10: exactly that many wrong-password attempts must
        // still come back as the ordinary 401, and only the next one is
        // throttled. Unknown emails are used so this test needs no user row
        // at all — every one of these ten answers 401 invalid-credentials on
        // its own, with no help from the throttle.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(loginAttempt(ip, "nobody-" + i + "@example.test", "wrong-password"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(loginAttempt(ip, "nobody-eleventh@example.test", "wrong-password"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("/problems/too-many-attempts"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("a successful login clears the counter, so failures after it start counting from zero again")
    void aSuccessfulLoginClearsTheCounterForThatIp() throws Exception {
        String ip = "203.0.113.20";
        Business business = businesses.save(new Business("Acme Renovations"));
        String email = uniqueEmail();
        users.save(new User(business, "Thora Throttle", email,
                passwordEncoder.encode("correct-password"), Role.OWNER));

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(loginAttempt(ip, email, "wrong-password"))
                    .andExpect(status().isUnauthorized());
        }

        // A success forgives the misses above; if it did not, the count would
        // sit at 5 rather than 0 afterwards.
        mockMvc.perform(loginAttempt(ip, email, "correct-password"))
                .andExpect(status().isOk());

        // A fresh run of MAX_FAILURES (10) failures now has to fit without
        // tripping the limit. If the earlier success had not cleared the
        // counter, this loop would hit 429 partway through instead of
        // finishing all ten as ordinary 401s.
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(loginAttempt(ip, email, "wrong-password"))
                    .andExpect(status().isUnauthorized());
        }
    }

    /** A login POST from a given IP, with the given (usually wrong) credentials. */
    private MockHttpServletRequestBuilder loginAttempt(String ip, String email, String password) {
        return post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "email": "%s",
                          "password": "%s"
                        }
                        """.formatted(email, password))
                // MockMvc's usual way to set the field LoginThrottleFilter
                // actually reads: request.getRemoteAddr(), the direct peer
                // address, not a header a caller could forge.
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                });
    }

    /** Emails are globally unique, so the one real user this class creates needs its own. */
    private String uniqueEmail() {
        return "throttle-" + UUID.randomUUID() + "@example.test";
    }
}
