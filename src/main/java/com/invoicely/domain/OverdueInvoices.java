package com.invoicely.domain;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * SENT &rarr; OVERDUE is automatic (Product Scope &sect;4): once an invoice's
 * due date has passed, nobody has to click anything for it to read as overdue.
 * That happens two ways, matching the Tech Scope's Task 5 note:
 *
 * <ul>
 *   <li>{@link #flipSentInvoicesPastDueToOverdue()} is a daily job that
 *       <em>persists</em> the transition, so a query — the invoice list, the
 *       dashboard's overdue count — can filter on {@code status = OVERDUE}
 *       without also reasoning about due dates.
 *   <li>{@link #asOf(Invoice, LocalDate)} is a pure read that keeps a single
 *       invoice honest in the gap the job leaves open: it runs once a day, so
 *       between midnight and the job actually running — or on a freshly
 *       restarted instance that hasn't reached its next run yet — a stored
 *       {@code SENT} row can already be overdue in reality before the job has
 *       caught up.
 * </ul>
 *
 * <p>"Past the due date" means strictly after: an invoice due today is not
 * overdue today, only from tomorrow. Both mechanisms below use the same
 * {@code dueDate < today} comparison so they can never disagree with each
 * other about the boundary.
 */
@Component
public class OverdueInvoices {

    private static final Logger log = LoggerFactory.getLogger(OverdueInvoices.class);

    /**
     * Just after midnight, so the day's date has definitely rolled over
     * everywhere the job might run. Hardcoded here rather than in
     * application.properties because that file is being edited by other
     * agents' work in parallel; a single daily job has no need to be
     * reconfigured per environment.
     *
     * <p>Cron fields are second, minute, hour, day-of-month, month,
     * day-of-week — so this is 00:05:00 every day.
     */
    private static final String OVERDUE_CRON = "0 5 0 * * *";

    private final EntityManager entityManager;

    OverdueInvoices(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Flips every SENT invoice whose due date is before today to OVERDUE,
     * across every business — this is a system job with no caller, so there
     * is no {@code CurrentRequest} to scope it to one business, and none is
     * needed since the job is meant to catch all of them.
     *
     * <p>Written as one bulk JPQL {@code UPDATE} rather than loading each
     * invoice and calling {@link Invoice#setStatus}, so a business with
     * thousands of overdue invoices costs one round trip, not one per row.
     *
     * <p>A bulk update runs straight against the database and bypasses the
     * persistence context — Hibernate does not load the affected rows, so it
     * has no in-memory {@link Invoice} objects to keep in step with what it
     * just wrote. That is exactly why it is safe here: this method has no
     * loaded entities for the database to fall out of agreement with, and
     * nothing else in the same transaction expects to see these invoices as
     * SENT. It would not be safe for a user-facing transition like
     * {@code send} or {@code reject}, which run inside a request that may
     * already hold the invoice loaded and go on to read or return it.
     *
     * @return how many invoices were flipped, so a caller — currently just
     *     this method's own log line — can report it
     */
    @Scheduled(cron = OVERDUE_CRON)
    @Transactional
    public int flipSentInvoicesPastDueToOverdue() {
        int updated = entityManager.createQuery("""
                        update Invoice i
                        set i.status = com.invoicely.domain.InvoiceStatus.OVERDUE
                        where i.status = com.invoicely.domain.InvoiceStatus.SENT
                          and i.dueDate < :today
                        """)
                .setParameter("today", BusinessCalendar.today())
                .executeUpdate();
        log.info("Overdue job: flipped {} invoice(s) from SENT to OVERDUE", updated);
        return updated;
    }

    /**
     * What {@code invoice}'s status should read as right now, without
     * changing anything.
     *
     * <p>The daily job above only runs once a day, so a stored {@code SENT}
     * invoice can be overdue in reality before the job has had a chance to
     * say so. Call this wherever a single invoice's status is shown or
     * checked and that gap would otherwise be visible — this method only
     * ever reports; it never writes to {@code invoice} or the database, so
     * calling it has no effect on when the job's own update happens.
     */
    public static InvoiceStatus asOf(Invoice invoice, LocalDate today) {
        if (invoice.getStatus() == InvoiceStatus.SENT && invoice.getDueDate().isBefore(today)) {
            return InvoiceStatus.OVERDUE;
        }
        return invoice.getStatus();
    }
}
