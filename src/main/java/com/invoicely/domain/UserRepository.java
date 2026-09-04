package com.invoicely.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Login lookup. Case-insensitive to match the unique index in V1, so
     * "Ada@example.com" finds the account registered as "ada@example.com".
     */
    Optional<User> findByEmailIgnoreCase(String email);
}
