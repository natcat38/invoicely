package com.invoicely.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Businesses are only ever loaded by their own id, taken from the caller's
 * identity, so this repository needs little beyond what JpaRepository provides.
 */
public interface BusinessRepository extends JpaRepository<Business, Long> {

    /**
     * Loads the business and holds a write lock on its row until the
     * transaction ends, which serialises invoice creation within that business
     * so two concurrent creates cannot pick the same sequence number.
     *
     * <p>See docs/adr/0004-invoice-numbering.md. The lock is per business, so
     * businesses never block each other; within one business, invoice creation
     * becomes single-file, which is the right trade at this scale — a small
     * business creates a handful of invoices a day, not a second.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Business b where b.id = :id")
    Optional<Business> findByIdForUpdate(@Param("id") Long id);
}
