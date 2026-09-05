package com.invoicely.web;

import com.invoicely.domain.Role;
import com.invoicely.domain.User;
import java.time.Instant;

/**
 * A team member as the owner's Team page lists them (Product Scope §2).
 *
 * <p>{@code invoicesCreated} and {@code lastActive} are Task 5's per-staff
 * aggregates: how many invoices this person has created, and the more recent
 * of their own {@code created_at} (drafting) and {@code sent_at} (sending) —
 * there is no separate activity-tracking table, per the Tech Scope. Both come
 * from {@link TeamService}, which computes them for the whole team in two
 * queries rather than one per row; this record just carries the numbers.
 * {@code lastActive} is null for someone who has done neither.
 */
public record StaffResponse(
        Long id,
        String name,
        String email,
        Role role,
        boolean active,
        Instant createdAt,
        long invoicesCreated,
        Instant lastActive) {

    /**
     * Builds a row without the aggregates filled in, for a caller that has not
     * looked them up. Real production code always follows this with
     * {@link #withActivity} — see {@link TeamService#list()} — but the
     * two-step shape keeps this class from having to know how the aggregates
     * are computed.
     */
    public static StaffResponse from(User user) {
        return new StaffResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole(),
                user.isActive(),
                user.getCreatedAt(),
                0,
                null);
    }

    /** Same row, with the per-staff aggregates filled in. */
    public StaffResponse withActivity(long invoicesCreated, Instant lastActive) {
        return new StaffResponse(id, name, email, role, active, createdAt, invoicesCreated, lastActive);
    }
}
