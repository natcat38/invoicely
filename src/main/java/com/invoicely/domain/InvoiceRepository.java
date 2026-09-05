package com.invoicely.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Invoice queries.
 *
 * <p>Several of these take a {@code today} parameter, and it is worth knowing
 * why. SENT becomes OVERDUE by the passage of time, and the nightly job in
 * {@link OverdueInvoices} is what writes that down. Between the moment an
 * invoice falls due and the moment the job next runs — up to a day — the stored
 * status still reads SENT while the invoice is overdue in fact.
 *
 * <p>So anything a person looks at computes the <em>effective</em> status
 * rather than trusting the column: {@code status = OVERDUE, or SENT and past
 * due}. The stored column still earns its keep — it is what keeps these queries
 * indexable, and what the job maintains — but it is never the last word.
 */
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** Ownership-scoped lookup — see ClientRepository for why. */
    Optional<Invoice> findByIdAndBusinessId(Long id, Long businessId);

    /**
     * Backs invoice numbering: INV-&lt;year&gt;-&lt;seq&gt; must be unique
     * within a business, though two businesses may use the same number. The
     * database enforces it too; this lets the service check before inserting.
     */
    boolean existsByBusinessIdAndNumber(Long businessId, String number);

    /**
     * Whether a client has ever been invoiced. Backs the archive rule: a client
     * with invoices cannot be deleted, because the invoices would lose their
     * counterparty.
     */
    boolean existsByClientId(Long clientId);

    /**
     * The highest number issued to this business in a given year, or null if it
     * has issued none. Sequences are zero-padded to four digits, so the highest
     * number sorts last as text and no parsing is needed to find it.
     */
    @Query("select max(i.number) from Invoice i "
            + "where i.business.id = :businessId and i.number like concat(:prefix, '%')")
    String highestNumber(@Param("businessId") Long businessId, @Param("prefix") String prefix);

    /**
     * The invoice list, in "needs attention" order: overdue first, then waiting
     * for approval, then everything else newest-first (Product Scope §5.3 — the
     * list is a to-do, not an archive).
     *
     * <p>Both the filter and the ordering use the effective status described on
     * this interface, not the stored one. Otherwise an invoice that fell due
     * this morning would sit under the SENT tab and sort as ordinary until
     * tonight's job caught up — while the row itself already displayed OVERDUE,
     * because the response computes it. The list would be quietly wrong in
     * exactly the case it exists to surface.
     *
     * <p>The filter is written out as plain boolean logic rather than as a CASE
     * returning an enum. It is longer, but each line says one thing, and it does
     * not rest on how a particular Hibernate version handles enum-valued CASE.
     *
     * <p>Both filters are optional: null means "any".
     */
    @Query("""
            select i from Invoice i
            where i.business.id = :businessId
              and (:clientId is null or i.client.id = :clientId)
              and (
                    :status is null
                 or (:status = com.invoicely.domain.InvoiceStatus.OVERDUE
                       and (i.status = com.invoicely.domain.InvoiceStatus.OVERDUE
                            or (i.status = com.invoicely.domain.InvoiceStatus.SENT
                                and i.dueDate < :today)))
                 or (:status = com.invoicely.domain.InvoiceStatus.SENT
                       and i.status = com.invoicely.domain.InvoiceStatus.SENT
                       and i.dueDate >= :today)
                 or (:status <> com.invoicely.domain.InvoiceStatus.OVERDUE
                       and :status <> com.invoicely.domain.InvoiceStatus.SENT
                       and i.status = :status)
              )
            order by case
                       when i.status = com.invoicely.domain.InvoiceStatus.OVERDUE
                            or (i.status = com.invoicely.domain.InvoiceStatus.SENT
                                and i.dueDate < :today) then 0
                       when i.status = com.invoicely.domain.InvoiceStatus.PENDING_APPROVAL then 1
                       else 2
                     end,
                     i.createdAt desc,
                     i.id desc
            """)
    Page<Invoice> findForList(@Param("businessId") Long businessId,
                              @Param("status") InvoiceStatus status,
                              @Param("clientId") Long clientId,
                              @Param("today") LocalDate today,
                              Pageable pageable);

    // --- Owner dashboard (GET /dashboard) and the Team page's per-staff
    // aggregates (GET /team). These are aggregate queries rather than loops in
    // Java: a dashboard that reads the whole invoice history to add up four
    // numbers gets slower every month the business trades.

    /**
     * Everything still owed on issued invoices — SENT and OVERDUE together, so
     * the effective-status distinction does not arise here: both are included
     * either way.
     *
     * <p>The GST factor is not decoration. An invoice's total is its subtotal
     * plus GST, so summing line items alone would understate a GST-registered
     * business by the entire tax amount — 9% wrong on the headline figure the
     * owner reads first. {@code gstRateSnapshot} is the right rate precisely
     * because every invoice in this sum has been sent, so its rate is already
     * frozen; null means the business was not registered, and {@code coalesce}
     * turns that into a factor of 1.
     *
     * <p>The arithmetic stays unrounded until the caller rounds the total, so
     * this can differ from summing the rounded per-invoice balances by a
     * fraction of a cent. That is the right trade for a headline figure;
     * anything that has to reconcile exactly should sum {@link InvoiceTotals}
     * per invoice instead.
     */
    @Query("""
            select coalesce(sum(
                (select coalesce(sum(li.quantity * li.unitPrice), 0)
                   from LineItem li where li.invoice = i) * (1 + coalesce(i.gstRateSnapshot, 0))
                - (select coalesce(sum(p.amount), 0) from Payment p where p.invoice = i)
            ), 0)
            from Invoice i
            where i.business.id = :businessId
              and i.status in (com.invoicely.domain.InvoiceStatus.SENT,
                               com.invoicely.domain.InvoiceStatus.OVERDUE)
            """)
    BigDecimal sumOutstandingBalance(@Param("businessId") Long businessId);

    /** The overdue slice of {@link #sumOutstandingBalance}, by effective status. */
    @Query("""
            select coalesce(sum(
                (select coalesce(sum(li.quantity * li.unitPrice), 0)
                   from LineItem li where li.invoice = i) * (1 + coalesce(i.gstRateSnapshot, 0))
                - (select coalesce(sum(p.amount), 0) from Payment p where p.invoice = i)
            ), 0)
            from Invoice i
            where i.business.id = :businessId
              and (i.status = com.invoicely.domain.InvoiceStatus.OVERDUE
                   or (i.status = com.invoicely.domain.InvoiceStatus.SENT and i.dueDate < :today))
            """)
    BigDecimal sumOverdueBalance(@Param("businessId") Long businessId,
                                 @Param("today") LocalDate today);

    /**
     * How many invoices are overdue right now — again by effective status, so
     * the count cannot disagree with the amount printed next to it.
     */
    @Query("""
            select count(i) from Invoice i
            where i.business.id = :businessId
              and (i.status = com.invoicely.domain.InvoiceStatus.OVERDUE
                   or (i.status = com.invoicely.domain.InvoiceStatus.SENT and i.dueDate < :today))
            """)
    long countOverdue(@Param("businessId") Long businessId, @Param("today") LocalDate today);

    /**
     * Revenue for a period, meaning money actually received — the sum of
     * payments, not of invoices issued. An invoice sent in January and paid in
     * March is March's revenue.
     */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from Payment p
            where p.invoice.business.id = :businessId
              and p.paidAt >= :from and p.paidAt < :toExclusive
            """)
    BigDecimal sumPaymentsReceived(@Param("businessId") Long businessId,
                                   @Param("from") LocalDate from,
                                   @Param("toExclusive") LocalDate toExclusive);

    /**
     * Per user: how many invoices they created, and when they last created one.
     * One row per user rather than one query per user, so the Team page costs
     * the same whether the business has two staff or two hundred.
     */
    @Query("""
            select i.createdBy.id, count(i), max(i.createdAt)
            from Invoice i
            where i.business.id = :businessId
            group by i.createdBy.id
            """)
    List<Object[]> countAndLastCreatedByUser(@Param("businessId") Long businessId);

    /**
     * Per user: when they last sent an invoice. Kept separate from
     * {@link #countAndLastCreatedByUser} because creating and sending are
     * different columns on different rows — one query would have to join the
     * table to itself to report both, for no gain at this size.
     */
    @Query("""
            select i.sentBy.id, max(i.sentAt)
            from Invoice i
            where i.business.id = :businessId and i.sentBy is not null
            group by i.sentBy.id
            """)
    List<Object[]> lastSentByUser(@Param("businessId") Long businessId);
}
