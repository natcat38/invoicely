package com.invoicely.web;

import com.invoicely.security.JwtService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Who is making this request, and which business they belong to.
 *
 * <p>ADR-0001 says the business id may only ever come from the caller's
 * identity, never from a request body or query parameter. This class is the one
 * place that identity is read — which is why Task 4 changed only this file to
 * move the whole application from development headers to real tokens. Every
 * controller and service was already asking the right question.
 *
 * <p>The claims are trusted because the token's signature has already been
 * verified by the time any of this runs. What is <em>not</em> read from the
 * token is anything that can change between logins — whether the account is
 * still active, whether the password still needs replacing. Those come from the
 * database on each request, in
 * {@link com.invoicely.security.AccountStateFilter}.
 */
@Component
public class CurrentRequest {

    /** The business every query in this request is scoped to. */
    public Long businessId() {
        Object claim = jwt().getClaim(JwtService.BUSINESS_CLAIM);
        if (claim instanceof Number businessId) {
            return businessId.longValue();
        }
        throw new UnidentifiedCallerException("This token carries no business.");
    }

    /** The user recorded as {@code created_by} on anything this request creates. */
    public Long userId() {
        try {
            return Long.valueOf(jwt().getSubject());
        } catch (NumberFormatException notOurToken) {
            throw new UnidentifiedCallerException("This token carries no usable user id.");
        }
    }

    private Jwt jwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            // Reached only if an endpoint that does not require authentication
            // asks who the caller is. Better to say so than to guess.
            throw new UnidentifiedCallerException("This request is not authenticated.");
        }
        return jwt;
    }
}
