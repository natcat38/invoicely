package com.invoicely.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    /** Ownership-scoped lookup — see ClientRepository for why. */
    Optional<Invoice> findByIdAndBusinessId(Long id, Long businessId);

    /**
     * Backs invoice numbering: INV-&lt;year&gt;-&lt;seq&gt; must be unique
     * within a business, though two businesses may use the same number. The
     * database enforces it too; this lets the service check before inserting.
     */
    boolean existsByBusinessIdAndNumber(Long businessId, String number);
}
