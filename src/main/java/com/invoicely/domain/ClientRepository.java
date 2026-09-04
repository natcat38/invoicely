package com.invoicely.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientRepository extends JpaRepository<Client, Long> {

    /**
     * The shape every single-row lookup in this application takes: id plus the
     * business id from the JWT. An empty result becomes a 404, whether the row
     * is missing or belongs to someone else — the two are indistinguishable to
     * the caller on purpose. See ADR-0001.
     */
    Optional<Client> findByIdAndBusinessId(Long id, Long businessId);
}
