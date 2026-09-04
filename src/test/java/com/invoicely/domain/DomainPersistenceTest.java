package com.invoicely.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicely.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Proves the domain model against a real PostgreSQL, started by Testcontainers
 * and migrated by Flyway.
 *
 * <p>Two settings do a lot of work here:
 *
 * <ul>
 *   <li>{@code replace = NONE} stops Spring swapping in an in-memory database.
 *       Half of what these tests check — numeric precision, case-insensitive
 *       unique indexes, CHECK constraints — only exists in PostgreSQL.
 *   <li>{@code ddl-auto=validate} means Hibernate refuses to start unless every
 *       entity matches the schema V1 created. So the class failing to load at
 *       all is itself the first assertion: entities and migration agree.
 * </ul>
 *
 * <p>Each test runs in a transaction that is rolled back afterwards, so they
 * cannot see each other.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class DomainPersistenceTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ClientRepository clientRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    private Business acme;
    private User acmeOwner;
    private Client acmeClient;

    @BeforeEach
    void createABusinessWithAnOwnerAndAClient() {
        acme = entityManager.persist(new Business("Acme Renovations"));
        acmeOwner = entityManager.persist(
                new User(acme, "Ada Owner", "ada@acme.example", "hash", Role.OWNER));
        acmeClient = entityManager.persist(new Client(acme, "Bright Cafe"));
    }

    @Test
    @DisplayName("an invoice saves and reloads with its line items and payments")
    void savesAndReloadsAWholeInvoice() {
        Invoice invoice = draftInvoice("INV-2026-0001");
        invoice.addLineItem("Site survey", new BigDecimal("2"), new BigDecimal("400.00"));
        invoice.addLineItem("Materials", new BigDecimal("1"), new BigDecimal("620.00"));
        invoice.addPayment(new BigDecimal("500.00"), LocalDate.of(2026, 3, 1),
                PaymentMethod.PAYNOW, acmeOwner);
        entityManager.persist(invoice);

        // Flush the inserts, then empty the persistence context so the next read
        // genuinely comes back from PostgreSQL rather than from memory.
        entityManager.flush();
        entityManager.clear();

        Invoice reloaded = invoiceRepository.findByIdAndBusinessId(invoice.getId(), acme.getId())
                .orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(reloaded.getGstRateSnapshot())
                .as("a draft has not been sent, so it has no snapshot yet")
                .isNull();
        assertThat(reloaded.getLineItems())
                .extracting(LineItem::getDescription)
                .containsExactly("Site survey", "Materials");
        assertThat(reloaded.getLineItems().getFirst().lineTotal())
                .isEqualByComparingTo("800.00");
        assertThat(reloaded.getPayments()).singleElement()
                .satisfies(payment -> {
                    assertThat(payment.getAmount()).isEqualByComparingTo("500.00");
                    assertThat(payment.getMethod()).isEqualTo(PaymentMethod.PAYNOW);
                });
    }

    @Test
    @DisplayName("money keeps four decimal places in the database, not the two it was written with")
    void moneyIsStoredAtScaleFour() {
        Invoice invoice = draftInvoice("INV-2026-0002");
        invoice.addLineItem("Consulting", new BigDecimal("1"), new BigDecimal("710.00"));
        entityManager.persist(invoice);
        entityManager.flush();
        entityManager.clear();

        BigDecimal unitPrice = entityManager.getEntityManager()
                .find(Invoice.class, invoice.getId())
                .getLineItems()
                .getFirst()
                .getUnitPrice();
        assertThat(unitPrice.scale())
                .as("NUMERIC(19,4) — rounding to two decimals happens at the API boundary")
                .isEqualTo(4);
        assertThat(unitPrice).isEqualByComparingTo("710.00");
    }

    @Test
    @DisplayName("removing a line item deletes the row and closes the gap it left")
    void removingALineItemDeletesItAndRenumbersTheRest() {
        Invoice invoice = draftInvoice("INV-2026-0003");
        invoice.addLineItem("First", BigDecimal.ONE, new BigDecimal("10.00"));
        LineItem middle = invoice.addLineItem("Second", BigDecimal.ONE, new BigDecimal("20.00"));
        invoice.addLineItem("Third", BigDecimal.ONE, new BigDecimal("30.00"));
        entityManager.persist(invoice);
        entityManager.flush();

        invoice.removeLineItem(middle);
        entityManager.flush();
        entityManager.clear();

        List<LineItem> remaining = entityManager.getEntityManager()
                .find(Invoice.class, invoice.getId())
                .getLineItems();
        assertThat(remaining).extracting(LineItem::getDescription)
                .containsExactly("First", "Third");
        assertThat(remaining).extracting(LineItem::getPosition)
                .as("positions stay dense, so ordering never depends on gaps")
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("another business cannot reach this invoice or client")
    void ownershipBoundaryHidesRowsFromOtherBusinesses() {
        Business other = entityManager.persist(new Business("Other Contractors"));
        Invoice invoice = draftInvoice("INV-2026-0004");
        entityManager.persist(invoice);
        entityManager.flush();

        // Same id, wrong business: the lookup comes back empty, which the web
        // layer turns into a 404. It never reports 403, because that would
        // confirm the row exists. See ADR-0001.
        assertThat(invoiceRepository.findByIdAndBusinessId(invoice.getId(), other.getId()))
                .isEmpty();
        assertThat(clientRepository.findByIdAndBusinessId(acmeClient.getId(), other.getId()))
                .isEmpty();

        assertThat(invoiceRepository.findByIdAndBusinessId(invoice.getId(), acme.getId()))
                .isPresent();
    }

    @Test
    @DisplayName("an invoice number is unique within a business but free to reuse in another")
    void invoiceNumbersAreUniquePerBusiness() {
        entityManager.persist(draftInvoice("INV-2026-0005"));
        entityManager.flush();

        Business other = entityManager.persist(new Business("Other Contractors"));
        User otherOwner = entityManager.persist(
                new User(other, "Ben Owner", "ben@other.example", "hash", Role.OWNER));
        Client otherClient = entityManager.persist(new Client(other, "Their Client"));
        entityManager.persist(new Invoice(other, otherClient, otherOwner, "INV-2026-0005",
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 3)));
        entityManager.flush();

        assertThat(invoiceRepository.existsByBusinessIdAndNumber(acme.getId(), "INV-2026-0005"))
                .isTrue();
        assertThat(invoiceRepository.existsByBusinessIdAndNumber(acme.getId(), "INV-2026-9999"))
                .isFalse();

        // The exception surfaces on persist, not on a later flush: because ids
        // are IDENTITY columns, Hibernate has to run the INSERT immediately to
        // learn the generated id, so it cannot batch the write until flush time.
        assertThatThrownBy(() -> entityManager.persist(draftInvoice("INV-2026-0005")))
                .as("UNIQUE (business_id, number) is the real guard, not the exists() check")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("an email is taken regardless of how it is capitalised")
    void emailsAreUniqueCaseInsensitively() {
        assertThat(userRepository.findByEmailIgnoreCase("ADA@ACME.EXAMPLE"))
                .as("login must not depend on the capitalisation the user typed")
                .contains(acmeOwner);

        // Different capitalisation, same account as far as the unique index on
        // lower(email) is concerned.
        assertThatThrownBy(() -> entityManager.persist(
                new User(acme, "Impostor", "Ada@Acme.Example", "hash", Role.STAFF)))
                .isInstanceOf(Exception.class);
    }

    /** A minimal DRAFT invoice for Acme, due 30 days after issue. */
    private Invoice draftInvoice(String number) {
        LocalDate issued = LocalDate.of(2026, 2, 1);
        return new Invoice(acme, acmeClient, acmeOwner, number, issued, issued.plusDays(30));
    }
}
