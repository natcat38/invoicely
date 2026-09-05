package com.invoicely.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.invoicely.TestcontainersConfiguration;
import com.invoicely.TestTokens;
import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Client;
import com.invoicely.domain.ClientRepository;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import com.invoicely.security.JwtService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the fix for the payment race described in
 * reports/phase1-audit/money-lifecycle-audit-raw.md: two payments racing
 * against the same invoice must not both be accepted if their sum would
 * overpay it.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional} at the class level, unlike
 * {@link PaymentApiTest} — a test transaction would bind every MockMvc call on
 * the main thread to one connection/transaction, which cannot demonstrate two
 * requests genuinely racing on the same row. Each thread's HTTP request runs
 * through {@link PaymentService}'s own {@code @Transactional} boundary and
 * gets its own connection, which is what lets the row lock in
 * {@code InvoiceRepository.findByIdAndBusinessIdForUpdate} actually serialise
 * them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentConcurrencyTest {

    private static final BigDecimal NINE_PERCENT = new BigDecimal("0.0900");

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
    private User acmeOwner;
    private Invoice invoice;

    @BeforeEach
    void seedASentInvoiceWithABalance() {
        acme = new Business("Acme Renovations");
        acme.setGstRegistered(true);
        acme.setGstRate(NINE_PERCENT);
        acme = businesses.save(acme);
        acmeOwner = users.save(new User(acme, "Ada Owner", uniqueEmail(), "hash", Role.OWNER));
        Client acmeClient = clients.save(new Client(acme, "Bright Cafe"));

        // A round S$1,000.00 balance (no GST snapshot), so two S$900.00
        // payments unambiguously overpay it if both were ever accepted.
        LocalDate issued = LocalDate.of(2026, 2, 1);
        Invoice draft = new Invoice(acme, acmeClient, acmeOwner, "INV-2026-0001",
                issued, issued.plusDays(30));
        draft.addLineItem("Work", BigDecimal.ONE, new BigDecimal("1000.00"));
        draft.setSentAt(Instant.now());
        draft.setSentBy(acmeOwner);
        draft.setStatus(InvoiceStatus.SENT);
        invoice = invoices.saveAndFlush(draft);
    }

    @Test
    @DisplayName("two concurrent payments that would together overpay the invoice: only one is accepted")
    void concurrentPaymentsCannotAggregateIntoAnOverpay() throws Exception {
        HttpHeaders headers = TestTokens.bearer(jwtService, acmeOwner);
        String body = paymentBody("900.00");

        // The barrier holds both threads at the same starting line, so the
        // second request's transaction begins as close as possible to the
        // first's, rather than trivially running after it commits.
        CyclicBarrier startingLine = new CyclicBarrier(2);
        Callable<Integer> fireOnePayment = () -> {
            startingLine.await();
            return mockMvc.perform(post("/invoices/" + invoice.getId() + "/payments")
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus();
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(fireOnePayment);
            Future<Integer> second = pool.submit(fireOnePayment);
            List<Integer> results = List.of(first.get(), second.get());

            // Exactly one S$900.00 payment fits in a S$1,000.00 balance. The
            // row lock forces the second request to wait for the first's
            // commit, then see the true remaining balance (S$100.00) instead
            // of the stale S$1,000.00 both would have read without it — so it
            // is correctly rejected as exceeding the balance, not accepted.
            assertThat(results).containsExactlyInAnyOrder(
                    HttpStatus.CREATED.value(), HttpStatus.BAD_REQUEST.value());
        } finally {
            pool.shutdown();
        }

        // Read back through the payment list endpoint rather than the entity
        // directly: outside the request that loaded it, Invoice.payments is a
        // lazy collection with no session left to initialise it.
        String paymentsJson = mockMvc.perform(get("/invoices/" + invoice.getId() + "/payments").headers(headers))
                .andReturn().getResponse().getContentAsString();
        assertThat(paymentsJson).contains("\"amount\":900.00").doesNotContain("1800.00");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(paymentsJson)).hasSize(1);
    }

    private String paymentBody(String amount) {
        return """
                {
                  "amount": "%s",
                  "paidAt": "2026-03-01",
                  "method": "BANK_TRANSFER",
                  "note": null
                }
                """.formatted(amount);
    }

    /** Emails are globally unique, so every seeded user needs its own. */
    private String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.test";
    }
}
