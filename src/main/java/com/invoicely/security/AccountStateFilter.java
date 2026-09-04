package com.invoicely.security;

import com.invoicely.domain.User;
import com.invoicely.domain.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks, on every authenticated request, the two things a token cannot be
 * trusted to know about itself.
 *
 * <p><b>Is the account still active?</b> A token stays valid until it expires,
 * so an owner who deactivates a staff member at 10:00 would otherwise be
 * ignored until that person's token ran out. The Tech Scope is explicit that
 * deactivation must bite on the next request, so the flag is read from the
 * database rather than from the token.
 *
 * <p><b>Does the password still need changing?</b> A staff member logging in
 * with their temporary password may do exactly one thing: replace it. Every
 * other endpoint answers 403 with a distinct problem type, which is the signal
 * the UI redirects on.
 *
 * <p>That is one query per authenticated request. At this scale it is the right
 * trade — correctness over a cache that could itself go stale, which is the bug
 * this filter exists to prevent. Cache it only with a measured reason to.
 */
@Component
public class AccountStateFilter extends OncePerRequestFilter {

    /**
     * The only thing a user with an unchanged temporary password may reach.
     *
     * <p>A matcher rather than a string comparison against
     * {@code getRequestURI()}: that value includes the servlet context path, so
     * deploying the application under {@code /api} would stop this matching and
     * lock those users out of the one endpoint that can free them.
     */
    private static final RequestMatcher CHANGE_PASSWORD =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/change-password");

    private final UserRepository users;
    private final ObjectMapper objectMapper;

    AccountStateFilter(UserRepository users, ObjectMapper objectMapper) {
        this.users = users;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        Optional<User> authenticated = currentUser();
        if (authenticated.isEmpty()) {
            // Anonymous, or a token this filter cannot make sense of. Either
            // way the request is somebody else's problem: an open endpoint will
            // serve it, and a protected one will reject it.
            chain.doFilter(request, response);
            return;
        }

        User user = authenticated.get();
        if (!user.isActive()) {
            reject(response, HttpStatus.UNAUTHORIZED, "account-deactivated",
                    "This account has been deactivated.");
            return;
        }
        if (user.isMustChangePassword() && !CHANGE_PASSWORD.matches(request)) {
            reject(response, HttpStatus.FORBIDDEN, "password-change-required",
                    "Set a new password to continue.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The user the bearer token names, if this request carries one. */
    private Optional<User> currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        try {
            return users.findById(Long.valueOf(jwt.getSubject()));
        } catch (NumberFormatException notOurToken) {
            return Optional.empty();
        }
    }

    /**
     * Writes the same Problem Details shape GlobalExceptionHandler produces.
     * A filter runs before any controller, so it cannot raise an exception for
     * that handler to catch — hence the small amount of repetition here.
     */
    private void reject(HttpServletResponse response, HttpStatus status, String type, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("/problems/" + type));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
