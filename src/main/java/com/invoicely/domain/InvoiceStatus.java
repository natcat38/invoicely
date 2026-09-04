package com.invoicely.domain;

/**
 * The five states an invoice moves through:
 * {@code DRAFT → PENDING_APPROVAL → SENT → (OVERDUE) → PAID}.
 *
 * <p>Which transitions are legal, and which role may drive each one, lands in
 * Task 5 along with the lifecycle endpoints. Today this enum only names the
 * states so invoices can be stored.
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
    PAID
}
