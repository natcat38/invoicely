package com.invoicely.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * <p>The ordering is a CASE expression rather than a column, because the
     * priority is a property of how the list is read, not of the invoice. Both
     * filters are optional: passing null for either means "any".
     *
     * <p>The sort is fixed rather than taken from the {@link Pageable}, since
     * nothing yet offers a different one. Alternative sorts can be added when a
     * screen actually asks for one.
     */
    @Query("""
            select i from Invoice i
            where i.business.id = :businessId
              and (:status is null or i.status = :status)
              and (:clientId is null or i.client.id = :clientId)
            order by case i.status
                       when com.invoicely.domain.InvoiceStatus.OVERDUE then 0
                       when com.invoicely.domain.InvoiceStatus.PENDING_APPROVAL then 1
                       else 2
                     end,
                     i.createdAt desc,
                     i.id desc
            """)
    Page<Invoice> findForList(@Param("businessId") Long businessId,
                              @Param("status") InvoiceStatus status,
                              @Param("clientId") Long clientId,
                              Pageable pageable);
}
