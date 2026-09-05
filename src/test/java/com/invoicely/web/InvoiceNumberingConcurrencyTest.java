package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/**
 * The concurrency guarantee {@link com.invoicely.domain.InvoiceNumbering} and
 * {@code InvoiceService.create}'s javadoc both promise: two invoices created
 * at the same moment for the same business, from separate connections, still
 * come out with distinct sequential numbers. See
 * docs/adr/0004-invoice-numbering.md.
 *
 * <p>Every other invoice test proves this only in the single-threaded sense —
 * called twice in a row, numbering counts up ({@code InvoiceApiTest}) or is
 * mocked one call at a time ({@code InvoiceNumberingTest}). Neither says
 * anything about {@code BusinessRepository.findByIdForUpdate} actually
 * serialising two requests that land at the same instant, which is the
 * failure mode that would otherwise only surface as an intermittent
 * unique-constraint violation in production.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> A transactional test
 * wraps the whole method in one connection, which would either serialise the
 * workers through that single connection or hide the very race this test
 * exists to catch. Each worker here drives a real HTTP request through
 * {@link MockMvc}, with its own signed bearer token, so each ends up on its
 * own connection and its own {@code InvoiceService.create} transaction — the
 * same shape a real burst of concurrent requests would have.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class InvoiceNumberingConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private ClientRepository clients;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private JwtService jwtService;

    private Business acme;
    private Client acmeClient;
    private HttpHeaders acmeHeaders;

    @BeforeEach
    void seedABusiness() {
        acme = businesses.save(new Business("Acme Renovations"));
        User owner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        acmeClient = clients.save(new Client(acme, "Bright Cafe"));
        acmeHeaders = TestTokens.bearer(jwtService, owner);
    }

    @Test
    @DisplayName("invoices created for the same business at the same instant still get distinct sequential numbers")
    void concurrentCreatesGetDistinctSequentialNumbers() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        // Every worker piles up on readyToStart, then all release on the same
        // "go" signal, so the requests genuinely overlap instead of trickling
        // in one at a time as threads happen to get scheduled — the point is
        // to actually contend for BusinessRepository.findByIdForUpdate's lock,
        // not just to run several creates in whatever order the pool likes.
        CountDownLatch readyToStart = new CountDownLatch(CONCURRENT_REQUESTS);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<String>> creates = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                creates.add(() -> {
                    readyToStart.countDown();
                    go.await();
                    return mockMvc.perform(post("/invoices")
                                    .headers(acmeHeaders)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(oneLineBody()))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getHeader("Location");
                });
            }

            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> create : creates) {
                futures.add(pool.submit(create));
            }
            assertThat(readyToStart.await(10, TimeUnit.SECONDS))
                    .as("the pool must actually have room to run every worker at once")
                    .isTrue();
            go.countDown();

            List<String> locations = new ArrayList<>();
            for (Future<String> future : futures) {
                locations.add(future.get(30, TimeUnit.SECONDS));
            }
            assertThat(locations)
                    .as("every request must have succeeded and returned a Location header")
                    .doesNotContainNull();
        } finally {
            pool.shutdown();
        }

        List<String> numbers = invoices.findAll().stream()
                .filter(invoice -> invoice.getBusiness().getId().equals(acme.getId()))
                .map(Invoice::getNumber)
                .toList();
        assertThat(numbers).hasSize(CONCURRENT_REQUESTS);
        assertThat(numbers)
                .as("no two concurrent creates can have read the same \"highest so far\" and "
                        + "produced the same number — the lock must have serialised them")
                .doesNotHaveDuplicates();
        assertThat(numbers)
                .as("distinct is not enough on its own: the sequence must also be gapless, "
                        + "i.e. exactly INV-2026-0001 through INV-2026-0010")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.IntStream.rangeClosed(1, CONCURRENT_REQUESTS)
                                .mapToObj(sequence -> "INV-2026-%04d".formatted(sequence))
                                .toList());
    }

    private String oneLineBody() {
        return """
                {
                  "issueDate": "2026-02-01",
                  "clientId": %d,
                  "lineItems": [{"description": "Work", "quantity": "1", "unitPrice": "100.00"}]
                }
                """.formatted(acmeClient.getId());
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
