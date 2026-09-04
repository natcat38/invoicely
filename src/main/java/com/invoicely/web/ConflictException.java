package com.invoicely.web;

import org.springframework.http.HttpStatus;

/**
 * The request is well-formed and permitted, but the thing it names is in a
 * state that does not allow it — a sent invoice being edited, or a client with
 * invoices being deleted.
 *
 * <p>409 is the state axis. The role axis (403) arrives with Task 4, and the
 * two are tested separately because they fail for unrelated reasons.
 */
public class ConflictException extends ApiException {

    public ConflictException(String type, String detail) {
        super(HttpStatus.CONFLICT, type, detail);
    }
}
