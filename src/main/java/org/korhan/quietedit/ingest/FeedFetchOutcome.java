package org.korhan.quietedit.ingest;

/** How a single feed request ended. */
public enum FeedFetchOutcome {

    /** 2xx with a body. */
    FETCHED,

    /** 304: the conditional request was answered "unchanged", so there is no body. */
    NOT_MODIFIED,

    /** Retries exhausted, a transport error, or a non-retryable status. */
    FAILED
}
