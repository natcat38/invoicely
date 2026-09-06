package com.invoicely;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the demo seeder actually seeds a believable business, and — the
 * important part, per the task this seeder exists for — that a restart does
 * not double anything.
 *
 * <p>Activates the {@code demo} profile for this test class only, and
 * supplies the password {@link DemoDataSeeder.DemoDataProperties} requires,
 * exactly as a real deployment must: see that record's Javadoc for why there
 * is no default to fall back on.
 *
 * <p><b>Deliberately not {@code @Transactional}.</b> {@link DemoDataSeeder#run}
 * commits its own transaction during application startup, before any test
 * method in this class runs — wrapping the test methods in one that rolls
 * back would not undo anything the seeder already wrote, and would also make
 * {@link #runningTheSeederAgainCreatesNothingNew}'s second, explicit call to
 * {@code run} behave differently from how it actually behaves on a real
 * restart against a real database.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("demo")
@TestPropertySource(properties = "invoicely.demo.password=a-password-for-this-test-run")
class DemoDataSeederTest {

    /** Matches {@link DemoDataSeeder.DemoDataProperties}'s own default. */
    private static final String OWNER_EMAIL = "owner@invoicely.demo";
    private static final String STAFF_EMAIL = "staff@invoicely.demo";

    @Autowired
    private DemoDataSeeder seeder;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private UserRepository users;

    @Autowired
    private InvoiceRepository invoices;

    @Test
    @DisplayName("startup seeds one business with a working owner and staff login")
    void seedsOneBusinessWithBothLogins() {
        User owner = users.findByEmailIgnoreCase(OWNER_EMAIL).orElseThrow();
        User staff = users.findByEmailIgnoreCase(STAFF_EMAIL).orElseThrow();

        assertThat(owner.getBusiness().getId()).isEqualTo(staff.getBusiness().getId());

        Business business = businesses.findById(owner.getBusiness().getId()).orElseThrow();
        assertThat(business.isGstRegistered()).isTrue();

        // Both accounts must be usable immediately: a recruiter clicking
        // "sign in as staff" cannot be handed a forced password-change screen
        // with no real "current password" to type — see the seeder's Javadoc
        // on seedStaff() for why this deliberately differs from TeamService.
        assertThat(owner.isMustChangePassword()).isFalse();
        assertThat(staff.isMustChangePassword()).isFalse();
        assertThat(owner.isActive()).isTrue();
        assertThat(staff.isActive()).isTrue();
    }

    @Test
    @DisplayName("running the seeder again, as a restart would, creates nothing new")
    void runningTheSeederAgainCreatesNothingNew() {
        long businessCountBefore = businesses.count();
        long userCountBefore = users.count();
        long invoiceCountBefore = invoices.count();

        seeder.run(new DefaultApplicationArguments());

        assertThat(businesses.count()).isEqualTo(businessCountBefore);
        assertThat(users.count()).isEqualTo(userCountBefore);
        assertThat(invoices.count()).isEqualTo(invoiceCountBefore);
    }

    /**
     * {@code @Transactional} here only, not on the class: {@link InvoiceTotals#of}
     * walks {@code lineItems} and {@code payments}, both lazy collections that
     * need an open Hibernate session to load ({@code spring.jpa.open-in-view}
     * is off) — a single transaction around this read-only method keeps that
     * session open for its whole body. The other two tests read only scalar
     * columns and the {@code business} id (cheap on a {@code ManyToOne} proxy
     * even with no session open), so they do not need this and are better off
     * without it — see the class Javadoc.
     */
    @Test
    @Transactional
    @DisplayName("the demo invoices cover every status, and the sent ones carry a GST snapshot")
    void seededInvoicesCoverEveryStatusWithAConsistentGstSnapshot() {
        Long demoBusinessId = users.findByEmailIgnoreCase(OWNER_EMAIL).orElseThrow().getBusiness().getId();
        List<Invoice> demoInvoices = invoices.findAll().stream()
                .filter(invoice -> invoice.getBusiness().getId().equals(demoBusinessId))
                .toList();

        Set<InvoiceStatus> statusesSeen = demoInvoices.stream()
                .map(Invoice::getStatus)
                .collect(Collectors.toSet());
        assertThat(statusesSeen).contains(InvoiceStatus.DRAFT, InvoiceStatus.PENDING_APPROVAL,
                InvoiceStatus.SENT, InvoiceStatus.OVERDUE, InvoiceStatus.PAID);

        // gst_rate_snapshot is only ever written when an invoice is sent
        // (InvoiceLifecycleService.send) — so every invoice that has moved
        // past DRAFT/PENDING_APPROVAL must carry one, or its totals would
        // silently disagree with what InvoiceTotals reports to the UI.
        List<Invoice> sentOrLater = demoInvoices.stream().filter(Invoice::hasBeenSent).toList();
        assertThat(sentOrLater).isNotEmpty();
        assertThat(sentOrLater).allSatisfy(invoice ->
                assertThat(invoice.getGstRateSnapshot()).as("gstRateSnapshot on " + invoice.getNumber())
                        .isNotNull());

        List<Invoice> paid = sentOrLater.stream()
                .filter(invoice -> invoice.getStatus() == InvoiceStatus.PAID)
                .toList();
        assertThat(paid).isNotEmpty();
        assertThat(paid).allSatisfy(invoice ->
                assertThat(InvoiceTotals.of(invoice).balance())
                        .as("balance on " + invoice.getNumber())
                        .isEqualByComparingTo(BigDecimal.ZERO));
    }
}
