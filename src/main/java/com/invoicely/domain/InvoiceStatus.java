package com.invoicely.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The five states an invoice moves through:
 * {@code DRAFT → PENDING_APPROVAL → SENT → (OVERDUE) → PAID}.
 *
 * <p>This enum answers one question — is this move legal at all? — and nothing
 * about <em>who</em> may make it. That separation is deliberate. An invoice has
 * two independent ways to refuse a request:
 *
 * <ul>
 *   <li><b>Wrong state</b> → 409. Nobody may send an invoice twice, whatever
 *       their role. That rule is here.
 *   <li><b>Wrong role</b> → 403. Staff may not send an invoice that is
 *       perfectly ready to be sent. That rule lives on the endpoints, as
 *       {@code @PreAuthorize}, next to the method it guards.
 * </ul>
 *
 * <p>Keeping them apart is what lets each be tested on its own, which the Tech
 * Scope calls for. When both would fail, the role check wins: it runs first,
 * before the method body, so a staff member is told they may not do this rather
 * than being told about the invoice's state.
 *
 * <p>Source of truth: the matrix in Product Scope §4.
 */
public enum InvoiceStatus {
    /** Editable. The only state in which line items may change. */
    DRAFT,
    /** Submitted by staff, waiting for the owner to approve or reject. */
    PENDING_APPROVAL,
    /** Issued to the client. The GST rate is snapshotted at this point. */
    SENT,
    /** Sent and past its due date. Set by a daily job, also computed on read. */
    OVERDUE,
    /** Payments cover the total exactly. Terminal. */
    PAID;

    /**
     * Every legal move, written out rather than derived, because the matrix in
     * Product Scope §4 is the specification — a rule that reproduced it by
     * cleverness would be harder to check against it.
     *
     * <p>Note what is absent: nothing returns from PAID, nothing goes back to
     * PENDING_APPROVAL once an invoice has left it, and OVERDUE is only ever
     * reached from SENT.
     */
    private static final Map<InvoiceStatus, Set<InvoiceStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(PENDING_APPROVAL, SENT),
            PENDING_APPROVAL, EnumSet.of(DRAFT, SENT),
            SENT, EnumSet.of(OVERDUE, PAID),
            OVERDUE, EnumSet.of(PAID),
            PAID, Collections.emptySet());

    /** Whether an invoice in this state may move to {@code target}. */
    public boolean canTransitionTo(InvoiceStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
