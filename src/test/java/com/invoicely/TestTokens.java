package com.invoicely;

import com.invoicely.domain.User;
import com.invoicely.security.JwtService;
import org.springframework.http.HttpHeaders;

/**
 * Builds the {@code Authorization} header a test needs to act as a given user.
 *
 * <p>The tokens are real — signed by the application's own {@link JwtService}
 * and verified by its own decoder — rather than a mocked security context. That
 * costs nothing and means the tests exercise the claim mapping, the role
 * conversion and {@code AccountStateFilter} exactly as a browser would.
 */
public final class TestTokens {

    private TestTokens() {
    }

    public static HttpHeaders bearer(JwtService jwtService, User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(user).token());
        return headers;
    }
}
