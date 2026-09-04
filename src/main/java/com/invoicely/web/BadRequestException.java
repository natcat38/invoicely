package com.invoicely.web;

import org.springframework.http.HttpStatus;

/**
 * The request itself is wrong — not the state of the thing it names.
 *
 * <p>Most bad input is caught by Bean Validation on the request record, which
 * produces a 400 listing the offending fields. This exists for the rules that
 * span two fields at once, which an annotation on a single field cannot see.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String type, String detail) {
        super(HttpStatus.BAD_REQUEST, type, detail);
    }
}
