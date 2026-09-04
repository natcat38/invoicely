package com.invoicely.domain;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Businesses are only ever loaded by their own id, taken from the JWT, so this
 * repository needs nothing beyond what JpaRepository already provides.
 */
public interface BusinessRepository extends JpaRepository<Business, Long> {
}
