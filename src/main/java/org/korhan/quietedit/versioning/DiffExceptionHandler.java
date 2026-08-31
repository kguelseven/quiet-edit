package org.korhan.quietedit.versioning;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns a refused diff request into Problem Details (RFC 7807).
 *
 * <p>Scoped to {@link DiffController} for the same reason
 * {@code IngestExceptionHandler} is scoped: a package-wide advice would quietly claim
 * exceptions from controllers added to this package later.
 */
@RestControllerAdvice(assignableTypes = DiffController.class)
class DiffExceptionHandler {

    /**
     * 404 for all three reasons, including the document observed only once. That case
     * is not a malformed request -- it is the ordinary state of most documents -- and
     * a 400 would tell the caller to fix something that is not wrong. What does not
     * exist is the diff, so the reason is carried in the problem type instead.
     */
    @ExceptionHandler(DiffUnavailableException.class)
    ProblemDetail unavailable(DiffUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("No diff available");
        problem.setType(URI.create("urn:quietedit:problem:" + e.reason().token()));
        return problem;
    }
}
