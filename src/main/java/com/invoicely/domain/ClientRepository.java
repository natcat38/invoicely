package com.invoicely.domain;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * The shape every single-row lookup in this application takes: id plus the
     * business id from the JWT. An empty result becomes a 404, whether the row
     * is missing or belongs to someone else — the two are indistinguishable to
     * the caller on purpose. See ADR-0001.
     */
    Optional<Client> findByIdAndBusinessId(Long id, Long businessId);

    /**
     * The client list: scoped to the business, filtered to one archive state at
     * a time (archived clients are hidden from the default view — Product Scope
     * §5.2), and optionally narrowed by a case-insensitive name search.
     *
     * <p>{@code namePattern} takes an already-lower-cased {@code %term%} wildcard
     * (built in {@code ClientService}) rather than the raw search word wrapped in
     * {@code concat()} here. Postgres cannot always infer a null parameter's type
     * when it only ever appears inside {@code concat(...)}, and falls back to
     * {@code bytea} — which then fails against {@code lower()}. Building the
     * pattern in Java sidesteps that entirely: the parameter is used in exactly
     * one place, a plain {@code like}, so its type is never in question.
     *
     * <p>{@code namePattern} is nullable rather than two overloaded methods, the
     * same optional-parameter shape {@code InvoiceRepository.findForList} uses:
     * one query to read instead of two near-duplicates to keep in sync.
     */
    @Query("""
            select c from Client c
            where c.business.id = :businessId
              and c.archived = :archived
              and (:namePattern is null or lower(c.name) like :namePattern)
            order by c.name asc
            """)
    Page<Client> findForList(@Param("businessId") Long businessId,
                             @Param("archived") boolean archived,
                             @Param("namePattern") String namePattern,
                             Pageable pageable);
}
