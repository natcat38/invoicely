package com.invoicely;

import com.invoicely.domain.Business;
import com.invoicely.domain.BusinessCalendar;
import com.invoicely.domain.BusinessRepository;
import com.invoicely.domain.Client;
import com.invoicely.domain.ClientRepository;
import com.invoicely.domain.Invoice;
import com.invoicely.domain.InvoiceNumbering;
import com.invoicely.domain.InvoiceRepository;
import com.invoicely.domain.InvoiceStatus;
import com.invoicely.domain.InvoiceTotals;
import com.invoicely.domain.Payment;
import com.invoicely.domain.PaymentMethod;
import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Populates one believable demo business the first time the {@code demo}
 * profile boots, so a recruiter who signs in sees a working history rather
 * than an empty shell.
 *
 * <p>Only active on the {@code demo} profile ({@link Profile}) — this must
 * never run in a test, a developer's own database, or a real deployment that
 * has not deliberately opted in, because it writes fake business and client
 * data under login credentials that are only safe to publish because they are
 * fake.
 *
 * <p><b>Idempotent by construction.</b> {@link #run} looks up the owner's
 * email first and returns immediately if it is already registered. A demo
 * environment restarts — a redeploy, a container recycling — and a restart
 * that quietly doubled every invoice would make the demo look broken rather
 * than fixing anything.
 *
 * <p>Builds entities directly through the repositories and the domain
 * helpers ({@link Invoice#addLineItem}, {@link Invoice#addPayment}) rather
 * than by calling {@code AuthService}/{@code TeamService}/{@code
 * InvoiceLifecycleService}. Those services are the right place for this logic
 * when a real HTTP request is behind it, but every one of them reads the
 * caller's identity from {@link com.invoicely.web.CurrentRequest}, which is
 * populated from a JWT on an incoming request — there is no request here,
 * only application startup. Re-deriving the handful of rules this seeder
 * actually needs (hash the password, freeze the GST rate at "send", flip the
 * status) is simpler than building a fake request context just to satisfy
 * those services' dependencies.
 */
@Component
@Profile("demo")
@EnableConfigurationProperties(DemoDataSeeder.DemoDataProperties.class)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final BusinessRepository businesses;
    private final UserRepository users;
    private final ClientRepository clients;
    private final InvoiceRepository invoices;
    private final InvoiceNumbering numbering;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataProperties properties;

    DemoDataSeeder(BusinessRepository businesses,
                   UserRepository users,
                   ClientRepository clients,
                   InvoiceRepository invoices,
                   InvoiceNumbering numbering,
                   PasswordEncoder passwordEncoder,
                   DemoDataProperties properties) {
        this.businesses = businesses;
        this.users = users;
        this.clients = clients;
        this.invoices = invoices;
        this.numbering = numbering;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * One transaction for the whole seed, so a failure partway through (an
     * unexpected constraint violation, say) leaves nothing half-created for
     * the idempotency check above to trip over on the next restart.
     */
    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.findByEmailIgnoreCase(properties.ownerEmail()).isPresent()) {
            log.info("Demo data seeder: a business already exists for owner {}, skipping.",
                    properties.ownerEmail());
            return;
        }

        Business business = seedBusiness();
        User owner = seedOwner(business);
        User staff = seedStaff(business);
        List<Client> demoClients = seedClients(business);
        seedInvoices(business, owner, staff, demoClients);

        log.info("Demo data seeder: created business '{}' with owner {} and staff {}.",
                business.getName(), properties.ownerEmail(), properties.staffEmail());
    }

    /**
     * GST-registered at 9%, with an address and UEN set so the invoice
     * document's letterhead is complete rather than the thinner one
     * ADR-0011 describes for a business that never filled those in.
     */
    private Business seedBusiness() {
        Business business = new Business("Marina Bay Renovations Pte Ltd");
        // Business's own constructor leaves gstRegistered at its default of
        // false and gstRate at 0.0900 — the rate a registered business would
        // typically charge, but inert until registration is switched on here.
        business.setGstRegistered(true);
        business.setDefaultPaymentTermsDays(30);
        business.setAddress("21 Tanjong Pagar Road, #03-05, Singapore 088444");
        business.setUen("201955432K");
        return businesses.save(business);
    }

    private User seedOwner(Business business) {
        User owner = new User(business, "Wei Ling Tan", properties.ownerEmail(),
                passwordEncoder.encode(properties.password()), Role.OWNER);
        // User.mustChangePassword defaults to false, which is correct here
        // unchanged: AuthService.register() never sets it either, because an
        // owner who registers has already chosen their own password.
        return users.save(owner);
    }

    private User seedStaff(Business business) {
        User staff = new User(business, "Marcus Lim", properties.staffEmail(),
                passwordEncoder.encode(properties.password()), Role.STAFF);
        // TeamService.create() forces mustChangePassword = true on every new
        // staff account, so a real hire has to prove they own the temporary
        // password before AccountStateFilter lets them touch anything else.
        // That is the wrong choice for this account: a recruiter signing in
        // as staff needs to see a working app on the very first click, not a
        // forced change-password screen with no real "current password" to
        // enter. Deliberately left at the class default of false instead.
        return users.save(staff);
    }

    /** Four Singapore clients with the address/UEN/payment details Product Scope §5.2 prints on a letterhead. */
    private List<Client> seedClients(Business business) {
        Client brightCafe = new Client(business, "Bright Cafe Pte Ltd");
        brightCafe.setContactPerson("Serene Ong");
        brightCafe.setEmail("serene.ong@brightcafe.sg");
        brightCafe.setPhone("+65 8123 4567");
        brightCafe.setAddress("18 Boon Tat Street, Singapore 069620");
        brightCafe.setUen("201812345K");
        brightCafe.setPaymentNotes("PayNow to UEN 201812345K, or bank transfer to DBS 003-456789-0.");

        Client harbourFront = new Client(business, "HarbourFront Dental Clinic");
        harbourFront.setContactPerson("Dr. Amirah Rashid");
        harbourFront.setEmail("admin@harbourfrontdental.sg");
        harbourFront.setPhone("+65 6273 8890");
        harbourFront.setAddress("1 Maritime Square, #02-14, Singapore 099253");
        harbourFront.setUen("201456789D");
        harbourFront.setPaymentNotes("Bank transfer to OCBC 501-234567-001 only, no PayNow.");

        Client tiongBahru = new Client(business, "Tiong Bahru Bakery Holdings");
        tiongBahru.setContactPerson("Jonathan Koh");
        tiongBahru.setEmail("jonathan@tiongbahrubakery.sg");
        tiongBahru.setPhone("+65 9012 3456");
        tiongBahru.setAddress("56 Eng Hoon Street, #01-70, Singapore 160056");
        tiongBahru.setUen("201699887W");
        tiongBahru.setPaymentNotes("PayNow to +65 9012 3456.");

        Client sentosaCove = new Client(business, "Sentosa Cove Residents' Association");
        sentosaCove.setContactPerson("Priya Nair");
        sentosaCove.setEmail("priya.nair@sentosacove.sg");
        sentosaCove.setPhone("+65 6333 2211");
        sentosaCove.setAddress("31 Ocean Drive, Singapore 098374");
        sentosaCove.setUen("201211223A");
        sentosaCove.setPaymentNotes("Cheque payable to 'Sentosa Cove RA', or PayNow to UEN 201211223A.");

        return List.of(
                clients.save(brightCafe),
                clients.save(harbourFront),
                clients.save(tiongBahru),
                clients.save(sentosaCove));
    }

    /**
     * Seven invoices spanning every status the invoice list can show, built
     * in the order Product Scope §4's lifecycle allows: a status further
     * along always has the ones before it (a SENT invoice is also, along the
     * way, a DRAFT) folded into how it is constructed here rather than
     * skipped to directly.
     *
     * <p>All dates come from {@link BusinessCalendar#today()}, never
     * {@code LocalDate.now()} — the two disagree for part of every day
     * because this runs in UTC while the business's "today" is Singapore
     * time, and that mismatch has already broken CI here once.
     */
    private void seedInvoices(Business business, User owner, User staff, List<Client> demoClients) {
        Client brightCafe = demoClients.get(0);
        Client harbourFront = demoClients.get(1);
        Client tiongBahru = demoClients.get(2);
        Client sentosaCove = demoClients.get(3);

        LocalDate today = BusinessCalendar.today();

        // 1. DRAFT — staff is still building this one. Nothing for the owner
        //    to see yet; it demonstrates line items can be edited freely.
        Invoice draft = newInvoice(business, brightCafe, staff, today, today.plusDays(30));
        draft.addLineItem("Kitchen exhaust hood replacement", new BigDecimal("1"), new BigDecimal("680.00"));
        draft.addLineItem("Grease trap servicing", new BigDecimal("2"), new BigDecimal("150.00"));
        invoices.save(draft);

        // 2. PENDING_APPROVAL — created and submitted by staff, so this is
        //    the row that populates the owner's approval queue and makes the
        //    maker-checker story visible on first login.
        Invoice pending = newInvoice(business, harbourFront, staff, today.minusDays(1), today.plusDays(29));
        pending.addLineItem("Waiting room flooring — vinyl plank", new BigDecimal("40"), new BigDecimal("32.50"));
        pending.addLineItem("Skirting and trim", new BigDecimal("1"), new BigDecimal("310.00"));
        pending.setStatus(InvoiceStatus.PENDING_APPROVAL);
        invoices.save(pending);

        // 3. SENT — issued, not yet due.
        Invoice sentSoon = newInvoice(business, tiongBahru, owner, today.minusDays(5), today.plusDays(25));
        sentSoon.addLineItem("Shopfront signage refresh", new BigDecimal("1"), new BigDecimal("1200.00"));
        sentSoon.addLineItem("LED lighting installation", new BigDecimal("6"), new BigDecimal("85.00"));
        send(sentSoon, business, owner);
        invoices.save(sentSoon);

        // 4. SENT — a second, smaller outstanding invoice, so the list does
        //    not read as "exactly one of everything".
        Invoice sentSmall = newInvoice(business, sentosaCove, staff, today.minusDays(2), today.plusDays(28));
        sentSmall.addLineItem("Quarterly gutter and drain clearing", new BigDecimal("1"), new BigDecimal("450.00"));
        send(sentSmall, business, owner);
        invoices.save(sentSmall);

        // 5. OVERDUE — sent well before its due date, which has now passed,
        //    with nothing paid against it.
        Invoice overdue = newInvoice(business, brightCafe, owner, today.minusDays(45), today.minusDays(15));
        overdue.addLineItem("Emergency plumbing repair — burst pipe", new BigDecimal("1"), new BigDecimal("890.00"));
        overdue.addLineItem("After-hours callout fee", new BigDecimal("1"), new BigDecimal("150.00"));
        send(overdue, business, owner);
        // OverdueInvoices#flipSentInvoicesPastDueToOverdue is the job that
        // normally makes this transition, once a day just after midnight. A
        // demo cannot wait for that cron to fire to look right, so this does
        // the same write the job would do, directly.
        overdue.setStatus(InvoiceStatus.OVERDUE);
        invoices.save(overdue);

        // 6. PAID — sent and settled in full, on time.
        Invoice paidOnTime = newInvoice(business, harbourFront, owner, today.minusDays(60), today.minusDays(30));
        paidOnTime.addLineItem("Reception desk rebuild", new BigDecimal("1"), new BigDecimal("2100.00"));
        paidOnTime.addLineItem("Waiting area seating x6", new BigDecimal("6"), new BigDecimal("175.00"));
        send(paidOnTime, business, owner);
        pay(paidOnTime, owner, paidOnTime.getIssueDate().plusDays(20),
                PaymentMethod.BANK_TRANSFER, "Paid by bank transfer, in full.");
        invoices.save(paidOnTime);

        // 7. PAID — a smaller job paid the same day it was sent, e.g. cash on
        //    completion, so the demo shows more than one shape of "paid".
        Invoice paidSameDay = newInvoice(business, tiongBahru, staff, today.minusDays(10), today.plusDays(20));
        paidSameDay.addLineItem("Oven repair — thermostat replacement", new BigDecimal("1"), new BigDecimal("320.00"));
        send(paidSameDay, business, owner);
        pay(paidSameDay, owner, paidSameDay.getIssueDate(),
                PaymentMethod.PAYNOW, "Paid via PayNow on completion.");
        invoices.save(paidSameDay);
    }

    /** Assigns the next {@code INV-<year>-<seq>} number, exactly as {@code InvoiceService.create} does. */
    private Invoice newInvoice(Business business, Client client, User createdBy,
                                LocalDate issueDate, LocalDate dueDate) {
        String number = numbering.next(business.getId(), issueDate);
        return new Invoice(business, client, createdBy, number, issueDate, dueDate);
    }

    /**
     * Re-does the two writes {@code InvoiceLifecycleService.send} makes: freeze
     * the GST rate onto the invoice (or leave it null, if the business is not
     * GST-registered — see {@link InvoiceTotals} for why that is not the same
     * as zero) and mark the invoice sent. Kept in step with that method by
     * hand, since it cannot be called directly here — see the class Javadoc.
     */
    private void send(Invoice invoice, Business business, User sentBy) {
        invoice.setGstRateSnapshot(business.isGstRegistered() ? business.getGstRate() : null);
        invoice.setSentAt(Instant.now());
        invoice.setSentBy(sentBy);
        invoice.setStatus(InvoiceStatus.SENT);
    }

    /**
     * Records a payment for the invoice's exact current total and marks it
     * PAID. Computed from {@link InvoiceTotals} — the same rounded figure the
     * API would show — rather than added up by hand here, so this can never
     * pay one cent more or less than the invoice actually asks for.
     *
     * <p>The method is a parameter rather than a constant because the demo is
     * read by people: a payment recorded as PayNow against a client whose own
     * invoice says "bank transfer only, no PayNow" is exactly the kind of
     * detail someone notices, and it makes the seeded data look careless.
     */
    private void pay(Invoice invoice, User recordedBy, LocalDate paidAt, PaymentMethod method,
                     String note) {
        BigDecimal total = InvoiceTotals.of(invoice).total();
        Payment payment = invoice.addPayment(total, paidAt, method, recordedBy);
        payment.setNote(note);
        invoice.setStatus(InvoiceStatus.PAID);
    }

    /**
     * The three settings this seeder needs, read from configuration rather
     * than hardcoded — matching how {@link com.invoicely.security.SecurityProperties}
     * does it.
     *
     * @param ownerEmail login for the demo OWNER account, defaulted so a
     *                    deployment needs no setup beyond the password below
     * @param staffEmail login for the demo STAFF account
     * @param password   shared password for both demo accounts. Deliberately
     *                    has no default: a demo with a password hardcoded
     *                    into a public repository is a real, guessable
     *                    credential the moment that repository is public, so
     *                    every deployment must choose its own. The compact
     *                    constructor below throws rather than falling back to
     *                    something guessable, which fails application startup
     *                    immediately and loudly instead of shipping a demo
     *                    nobody can safely point at the internet.
     */
    @ConfigurationProperties(prefix = "invoicely.demo")
    record DemoDataProperties(String ownerEmail, String staffEmail, String password) {

        DemoDataProperties {
            if (ownerEmail == null || ownerEmail.isBlank()) {
                ownerEmail = "owner@invoicely.demo";
            }
            if (staffEmail == null || staffEmail.isBlank()) {
                staffEmail = "staff@invoicely.demo";
            }
            if (password == null || password.isBlank()) {
                throw new IllegalStateException(
                        "invoicely.demo.password must be set when the 'demo' profile is active. "
                                + "Set INVOICELY_DEMO_PASSWORD (or invoicely.demo.password) to a "
                                + "password chosen for this deployment — there is no default, "
                                + "because a hardcoded one would be a real, public credential.");
            }
        }
    }
}
