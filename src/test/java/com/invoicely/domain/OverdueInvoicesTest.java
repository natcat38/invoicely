package com.invoicely.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicely.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Proves the overdue transition against a real PostgreSQL, the same way
 * {@link DomainPersistenceTest} proves the rest of the domain model — see
 * that class for why {@code replace = NONE} and {@code ddl-auto=validate}
 * matter here.
 *
 * <p>The job is called directly rather than waiting for the scheduler: the
 * cron expression on {@link OverdueInvoices#flipSentInvoicesPastDueToOverdue()}
 * only decides when Spring calls the method, not what the method does, so
 * calling it straight from the test proves the same behaviour without an
 * actual overnight wait.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// @DataJpaTest only scans entities and repositories by default, so the
// service under test has to be pulled in explicitly, same as
// TestcontainersConfiguration.
@Import({TestcontainersConfiguration.class, OverdueInvoices.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=validate")
class OverdueInvoicesTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private OverdueInvoices overdueInvoices;

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
    @DisplayName("a SENT invoice due yesterday flips to OVERDUE")
    void sentInvoiceDueYesterdayFlipsToOverdue() {
        drainPreExistingOverdue();
        Invoice invoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0001",
                LocalDate.now().minusDays(1), InvoiceStatus.SENT);
        entityManager.persist(invoice);
        entityManager.flush();

        int updated = overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        assertThat(reload(invoice).getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    @DisplayName("a SENT invoice due today is not touched — overdue means strictly past due")
    void sentInvoiceDueTodayIsNotOverdue() {
        drainPreExistingOverdue();
        Invoice invoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0002",
                LocalDate.now(), InvoiceStatus.SENT);
        entityManager.persist(invoice);
        entityManager.flush();

        int updated = overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();

        assertThat(updated).isZero();
        assertThat(reload(invoice).getStatus()).isEqualTo(InvoiceStatus.SENT);
    }

    @Test
    @DisplayName("a DRAFT invoice past its due date is untouched — only SENT invoices go overdue")
    void draftInvoicePastDueIsUntouched() {
        Invoice invoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0003",
                LocalDate.now().minusDays(1), InvoiceStatus.DRAFT);
        entityManager.persist(invoice);
        entityManager.flush();

        overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();

        assertThat(reload(invoice).getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    @DisplayName("a PENDING_APPROVAL invoice past its due date is untouched")
    void pendingApprovalInvoicePastDueIsUntouched() {
        Invoice invoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0004",
                LocalDate.now().minusDays(1), InvoiceStatus.PENDING_APPROVAL);
        entityManager.persist(invoice);
        entityManager.flush();

        overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();

        assertThat(reload(invoice).getStatus()).isEqualTo(InvoiceStatus.PENDING_APPROVAL);
    }

    @Test
    @DisplayName("a PAID invoice past its due date is untouched")
    void paidInvoicePastDueIsUntouched() {
        Invoice invoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0005",
                LocalDate.now().minusDays(1), InvoiceStatus.PAID);
        entityManager.persist(invoice);
        entityManager.flush();

        overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();

        assertThat(reload(invoice).getStatus()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    @DisplayName("the job runs across every business, not just one")
    void jobSpansBusinesses() {
        drainPreExistingOverdue();
        Invoice acmeInvoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0006",
                LocalDate.now().minusDays(1), InvoiceStatus.SENT);
        entityManager.persist(acmeInvoice);

        Business other = entityManager.persist(new Business("Other Contractors"));
        User otherOwner = entityManager.persist(
                new User(other, "Ben Owner", "ben@other.example", "hash", Role.OWNER));
        Client otherClient = entityManager.persist(new Client(other, "Their Client"));
        Invoice otherInvoice = invoiceDueOn(other, otherClient, otherOwner, "INV-2026-0001",
                LocalDate.now().minusDays(1), InvoiceStatus.SENT);
        entityManager.persist(otherInvoice);
        entityManager.flush();

        int updated = overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();

        assertThat(updated).isEqualTo(2);
        assertThat(reload(acmeInvoice).getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(reload(otherInvoice).getStatus()).isEqualTo(InvoiceStatus.OVERDUE);
    }

    @Test
    @DisplayName("asOf reports OVERDUE for a stored-SENT invoice past its due date, without saving it")
    void asOfReportsOverdueWithoutMutating() {
        Invoice invoice = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0007",
                LocalDate.now().minusDays(1), InvoiceStatus.SENT);
        entityManager.persist(invoice);
        entityManager.flush();

        assertThat(OverdueInvoices.asOf(invoice, LocalDate.now())).isEqualTo(InvoiceStatus.OVERDUE);

        // Nothing was written: the stored row still says SENT, since only the
        // scheduled job persists the transition.
        entityManager.clear();
        assertThat(reload(invoice).getStatus()).isEqualTo(InvoiceStatus.SENT);
    }

    @Test
    @DisplayName("asOf reports the stored status when the invoice isn't a past-due SENT one")
    void asOfReportsStoredStatusOtherwise() {
        Invoice dueToday = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0008",
                LocalDate.now(), InvoiceStatus.SENT);
        Invoice paid = invoiceDueOn(acme, acmeClient, acmeOwner, "INV-2026-0009",
                LocalDate.now().minusDays(1), InvoiceStatus.PAID);

        assertThat(OverdueInvoices.asOf(dueToday, LocalDate.now())).isEqualTo(InvoiceStatus.SENT);
        assertThat(OverdueInvoices.asOf(paid, LocalDate.now())).isEqualTo(InvoiceStatus.PAID);
    }

    /** An invoice due on {@code dueDate}, saved directly in {@code status} without going through the lifecycle. */
    private Invoice invoiceDueOn(Business business, Client client, User owner, String number,
                                 LocalDate dueDate, InvoiceStatus status) {
        LocalDate issued = dueDate.minusDays(30);
        Invoice invoice = new Invoice(business, client, owner, number, issued, dueDate);
        invoice.addLineItem("Work", BigDecimal.ONE, new BigDecimal("100.00"));
        invoice.setStatus(status);
        return invoice;
    }

    private Invoice reload(Invoice invoice) {
        return entityManager.getEntityManager().find(Invoice.class, invoice.getId());
    }

    /**
     * Flips anything already overdue, so the counts asserted below describe
     * only the invoices this test just created.
     *
     * <p>The job deliberately spans every business — it has no caller and so no
     * business to scope to — and JourneyTest commits real invoices into this
     * same database rather than rolling back. Asserting a raw count would
     * therefore be asserting something this test does not control. Draining
     * first makes the measurement a delta. It is safe because @DataJpaTest
     * rolls the whole test back afterwards.
     */
    private void drainPreExistingOverdue() {
        overdueInvoices.flipSentInvoicesPastDueToOverdue();
        entityManager.clear();
    }
}
