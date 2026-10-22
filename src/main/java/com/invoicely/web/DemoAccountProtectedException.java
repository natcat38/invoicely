package com.invoicely.web;

import org.springframework.http.HttpStatus;

/**
 * Someone tried to change the credentials of, or switch off, one of the
 * shared public demo logins.
 *
 * <p>403 rather than 409: the caller is signed in and the request is
 * well-formed, but this particular action is never permitted on these
 * accounts, whatever state they are in — a rule about who may do what (the
 * role axis), not about the account's current state. See
 * {@link DemoAccountGuard} for why the rule exists.
 */
public class DemoAccountProtectedException extends ApiException {

    public DemoAccountProtectedException(String detail) {
        super(HttpStatus.FORBIDDEN, "demo-account-protected", detail);
    }
}
