package com.invoicely.web;

import org.springframework.http.HttpStatus;

/**
 * The row does not exist, or it belongs to another business — the API refuses
 * to say which, because saying so would confirm the row exists. See ADR-0001.
 */
public class NotFoundException extends ApiException {

    public NotFoundException(String what) {
        super(HttpStatus.NOT_FOUND, "not-found", what + " was not found.");
    }
}
