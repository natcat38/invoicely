package com.invoicely.web;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 Problem Details, so every error in this API
 * has the same shape:
 *
 * <pre>
 * {"type": "/problems/not-found", "title": "Not Found", "status": 404,
 *  "detail": "Invoice was not found."}
 * </pre>
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own
 * failures — unreadable JSON, wrong content type, missing parameters — come
 * back in that shape too, instead of the default HTML error page.
 *
 * <p>The Product Scope calls for validation to be enforced twice, in the UI for
 * convenience and here authoritatively (§5.5). This is the authoritative half,
 * which is why a 400 lists every field that failed rather than stopping at the
 * first: the form needs to mark all of them at once.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException exception) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setType(URI.create("/problems/" + exception.getType()));
        return problem;
    }

    /**
     * A caller who is authenticated, and in the right business, but whose role
     * does not permit this action — {@code @PreAuthorize} rejecting a STAFF
     * token on an owner-only endpoint.
     *
     * <p>Without this, Spring Security answers with its own bare 403 body, and
     * the API would have two different error shapes depending on which layer
     * said no. It stays a 403: this is the role axis. The state axis is 409,
     * and a row belonging to another business is 404 (ADR-0001) — three
     * separate failures that must never be conflated.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    ProblemDetail handleAuthorizationDenied(AuthorizationDeniedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Your role does not allow this.");
        problem.setType(URI.create("/problems/insufficient-role"));
        return problem;
    }

    /**
     * A request body that failed Bean Validation. The generic 400 is kept, and
     * an {@code errors} member is added listing one entry per invalid field.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        List<FieldProblem> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(error.getField(), message(error)))
                // Sorted so the response is stable, which makes it testable.
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::message))
                .toList();

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Some fields need fixing.");
        problem.setType(URI.create("/problems/validation-failed"));
        problem.setProperty("errors", fields);
        return ResponseEntity.badRequest().body(problem);
    }

    private String message(FieldError error) {
        return error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage();
    }

    /** One invalid field, named the way the request body names it. */
    public record FieldProblem(String field, String message) {
    }
}
