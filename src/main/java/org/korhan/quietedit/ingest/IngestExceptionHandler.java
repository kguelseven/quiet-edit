package org.korhan.quietedit.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Turns the ingest package's refusals into Problem Details (RFC 7807).
 *
 * <p>Scoped to {@link IngestController} rather than registered globally: a refusal
 * only means something to the endpoint that could have triggered a run, and a
 * package-wide advice would quietly claim exceptions from controllers added later.
 */
@RestControllerAdvice(assignableTypes = IngestController.class)
class IngestExceptionHandler {

    /**
     * 409 rather than 429: the trigger is not rate limited, it collides with a run
     * that is already doing the work the caller asked for. Retrying after it ends
     * is the caller's decision, and nothing here can say when that will be.
     */
    @ExceptionHandler(IngestAlreadyRunningException.class)
    ProblemDetail alreadyRunning(IngestAlreadyRunningException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Ingest run already in progress");
        problem.setType(URI.create("urn:quietedit:problem:ingest-already-running"));
        return problem;
    }
}
