package com.invoicely.web;

import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import java.time.Instant;

/**
 * A team member as the owner's Team page lists them (Product Scope §2).
 *
 * <p>The page also wants, per staff member, an invoices-created count and a
 * last-active date. Those are aggregate queries over invoices — the Tech
 * Scope places them under Task 5's dashboard aggregates, not here, so this
 * response deliberately stops short of them.
 */
public record StaffResponse(
        Long id,
        String name,
        String email,
        Role role,
        boolean active,
        Instant createdAt) {

    public static StaffResponse from(User user) {
        return new StaffResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt());
    }
}
