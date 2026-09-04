package com.invoicely.domain;

/**
 * What a user is allowed to do. Registration creates the OWNER; the owner
 * creates STAFF. There is exactly one role per user and it does not change.
 *
 * <p>The permission split is in the Product Scope §3 matrix: staff manage
 * clients and build draft invoices, owners additionally approve, send, record
 * payments, see revenue, and manage the team.
 */
public enum Role {
    OWNER,
    STAFF
}
