package com.invoicely.web;

import org.springframework.http.HttpStatus;

/**
 * Base class for the failures this API reports deliberately, as opposed to the
 * ones that mean something is broken.
 *
 * <p>Each carries the status it becomes and a short {@code type} slug. The slug
 * ends up in the Problem Details {@code type} field, which is the part a client
 * is allowed to branch on — the human-readable {@code detail} is for people and
 * may be reworded at any time.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;

    protected ApiException(HttpStatus status, String type, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Stable identifier for this kind of failure, e.g. {@code not-found}. */
    public String getType() {
        return type;
    }
}
