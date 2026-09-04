package com.invoicely.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Who is making this request, and which business they belong to.
 *
 * <p>ADR-0001 says the business id may only ever come from the caller's
 * identity, never from a request body or query parameter. This class is the one
 * place that identity is read, so there is exactly one thing for Task 4 to
 * replace: today it reads two development-only headers, and afterwards it will
 * read the {@code biz} and {@code sub} claims off the JWT.
 *
 * <p><b>The headers are a stand-in, not a feature.</b> They are trivially
 * forgeable, which is fine only because this slice runs behind a permit-all
 * {@link com.invoicely.SecurityConfig} that is itself not deployable. Nothing
 * outside this class knows they exist, so swapping the source of truth does not
 * touch a single controller or service.
 */
@Component
public class CurrentRequest {

    static final String BUSINESS_HEADER = "X-Business-Id";
    static final String USER_HEADER = "X-User-Id";

    private final HttpServletRequest request;

    CurrentRequest(HttpServletRequest request) {
        this.request = request;
    }

    /** The business every query in this request is scoped to. */
    public Long businessId() {
        return required(BUSINESS_HEADER);
    }

    /** The user recorded as {@code created_by} on anything this request creates. */
    public Long userId() {
        return required(USER_HEADER);
    }

    private Long required(String header) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            throw new UnidentifiedCallerException(
                    "This build has no login yet, so it needs the development header "
                            + header + ". Task 4 replaces it with a JWT.");
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new UnidentifiedCallerException(header + " must be a number.");
        }
    }
}
