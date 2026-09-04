package com.invoicely.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Login lookup. Case-insensitive to match the unique index in V1, so
     * "Ada@example.com" finds the account registered as "ada@example.com".
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Ownership-scoped lookup, used wherever a user id arrives with a request.
     * Even audit fields like {@code created_by} go through this rather than
     * plain {@code findById}: a user from another business must not be able to
     * end up attributed on this business's invoice. See ADR-0001.
     */
    Optional<User> findByIdAndBusinessId(Long id, Long businessId);
}
