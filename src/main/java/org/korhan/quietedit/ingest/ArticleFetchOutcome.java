package org.korhan.quietedit.ingest;

/** How one article request ended. */
public enum ArticleFetchOutcome {

    /** 2xx, HTML, body stored; the only outcome that yields a {@code rawHtmlRef}. */
    FETCHED,

    /** The response was a PDF, an image or another binary: nothing to version. */
    SKIPPED_NOT_HTML,

    /** robots.txt forbids this URL to us. Not a failure -- the correct answer is "don't". */
    BLOCKED_BY_ROBOTS,

    /** Retries exhausted, a transport error, a non-retryable status, or a redirect chain that never ended. */
    FAILED
}
