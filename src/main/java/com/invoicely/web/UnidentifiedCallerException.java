package com.invoicely.web;

import org.springframework.http.HttpStatus;

/**
 * The caller could not be identified, so there is no business to scope the
 * request to. Temporary: today it means the development headers were missing,
 * and from Task 4 it means the JWT was missing or unusable.
 */
public class UnidentifiedCallerException extends ApiException {

    public UnidentifiedCallerException(String detail) {
        super(HttpStatus.UNAUTHORIZED, "unidentified-caller", detail);
    }
}
