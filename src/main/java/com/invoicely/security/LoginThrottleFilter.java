package com.invoicely.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Slows down guessing against the two endpoints a caller reaches with no
 * token at all: {@code POST /auth/login} and {@code POST /auth/register}. See
 * docs/adr/0010-session-invalidation-and-login-throttling.md.
 *
 * <p>A fixed window, per client IP address, counting only failed attempts —
 * so a busy office behind one NAT address is never punished for logging in
 * successfully, only for guessing wrong.
 */
@Component
public class LoginThrottleFilter extends OncePerRequestFilter {

    private static final RequestMatcher LOGIN =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/login");
    private static final RequestMatcher REGISTER =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/register");

    /**
     * Generous enough that a real person fat-fingering their own password a
     * few times in a row is never blocked; tight enough that guessing is
     * pointless on top of the tens of milliseconds BCrypt already costs per
     * attempt (AuthService.DUMMY_HASH — every attempt pays that cost, correct
     * guess or not).
     */
    private static final int MAX_FAILURES = 10;

    private static final Duration WINDOW = Duration.ofMinutes(15);

    /**
     * Every distinct source IP that has failed at least once in the current
     * window gets an entry, so an attacker (or a botnet) rotating through
     * enough addresses could otherwise grow this map without bound. 10,000 is
     * far more than a small demo application sees from genuine callers in a
     * 15-minute window; past it, expired entries are swept before a new IP is
     * tracked. This is per instance and lost on restart — it is a
     * {@link ConcurrentHashMap}, not a shared store — which is the honest
     * limit of a no-new-dependency solution (ADR-0010). The upgrade path is a
     * shared store behind this same class, not a rewrite of it.
     */
    private static final int MAX_TRACKED_IPS = 10_000;

    private final ObjectMapper objectMapper;

    /** One fixed window per client IP. See {@link Window}. */
    private final Map<String, Window> attemptsByIp = new ConcurrentHashMap<>();

    LoginThrottleFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Restricting the filter to exactly the two throttled endpoints here,
     * rather than checking inside {@link #doFilterInternal}, means every
     * other request skips this filter without ever touching
     * {@link #attemptsByIp} — no map lookup, no risk of one path's traffic
     * crowding out another's budget by accident.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOGIN.matches(request) && !REGISTER.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        // The direct TCP peer, not anything a caller could set themselves.
        // Behind a reverse proxy every real caller would land in this one
        // bucket unless X-Forwarded-For is read instead — and reading that
        // header without a configured list of trusted proxies would let any
        // caller claim whatever address they like and step straight around
        // this limit. That is a real gap, named here rather than half-closed
        // with an unverified header; see ADR-0010.
        String ip = request.getRemoteAddr();
        Instant now = Instant.now();

        Window window = attemptsByIp.get(ip);
        if (window != null && !window.isExpired(now) && window.failureCount() >= MAX_FAILURES) {
            reject(response, window.secondsUntilExpiry(now));
            return;
        }

        chain.doFilter(request, response);

        if (response.getStatus() >= 400) {
            recordFailure(ip, now);
        } else {
            // A successful attempt forgives whatever came before it — the
            // budget is for guessing, not for a slow typist.
            attemptsByIp.remove(ip);
        }
    }

    /**
     * Adds one failure for {@code ip}, starting a fresh window if there was
     * none yet or the previous one has rolled over.
     *
     * <p>{@code compute} rather than a read-then-write, so two failing
     * requests from the same IP arriving at the same instant cannot race each
     * other and lose an increment — the whole point of a limit is that it
     * cannot be undercounted by exactly the kind of concurrent traffic an
     * attacker would send.
     */
    private void recordFailure(String ip, Instant now) {
        if (!attemptsByIp.containsKey(ip) && attemptsByIp.size() >= MAX_TRACKED_IPS) {
            // Eviction runs as its own pass over the map, not from inside the
            // compute() below: ConcurrentHashMap's contract asks a
            // remapping function not to touch any mapping but the one it was
            // given.
            attemptsByIp.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        }
        attemptsByIp.compute(ip, (key, existing) -> (existing == null || existing.isExpired(now))
                ? new Window(now, 1)
                : existing.incremented());
    }

    /**
     * Writes the same Problem Details shape {@code AccountStateFilter} does —
     * a filter runs before any controller, so it cannot raise an exception for
     * {@code GlobalExceptionHandler} to catch.
     */
    private void reject(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Try again later.");
        problem.setType(URI.create("/problems/too-many-attempts"));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    /**
     * One IP's fixed window: when it started, and how many failures have
     * landed in it since. Immutable, so {@code compute} above can swap one in
     * for another without either request seeing a half-updated count.
     */
    private record Window(Instant start, int failureCount) {

        boolean isExpired(Instant now) {
            return !now.isBefore(start.plus(WINDOW));
        }

        Window incremented() {
            return new Window(start, failureCount + 1);
        }

        /** Rounded up, so a caller is never told to retry a moment too early. */
        long secondsUntilExpiry(Instant now) {
            return Duration.between(now, start.plus(WINDOW)).plusSeconds(1).toSeconds();
        }
    }
}
