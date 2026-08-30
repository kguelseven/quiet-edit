package org.korhan.quietedit.ingest;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of following one article link.
 *
 * <p>{@code finalUrl} is the URL the redirect chain ended at, and it -- not the URL
 * the feed advertised -- is what identity is derived from downstream: publishers
 * routinely publish a short link or a tracking hop, and canonicalising the
 * advertised URL would mint a new document every time the redirect target changes.
 * The whole {@code redirectChain} is kept because "which hop rewrote the URL" is the
 * only way to diagnose an identity that drifted.
 *
 * <p>No HTML is carried here, only {@code rawHtmlRef}. The body is on disk in
 * {@link RawHtmlStore} by the time this record exists, and a run fetches hundreds of
 * articles concurrently -- holding every one of them in memory until the run
 * finishes would make peak memory a function of feed size. Whoever needs the markup
 * reads it back through the store, which is also the copy that survives the process.
 *
 * <p>Nothing is parsed: this ticket's boundary is retrieval, so the page title and
 * the article text are absent by design.
 */
public record ArticleFetchResult(
        String requestedUrl,
        String finalUrl,
        ArticleFetchOutcome outcome,
        Instant fetchedAt,
        Integer httpStatus,
        String contentType,
        String rawHtmlRef,
        int contentLength,
        List<String> redirectChain,
        int attempts,
        String failureReason) {

    public ArticleFetchResult {
        redirectChain = List.copyOf(redirectChain);
    }

    public static ArticleFetchResult fetched(String requestedUrl, String finalUrl, Instant fetchedAt,
                                             int httpStatus, String contentType, String rawHtmlRef,
                                             int contentLength, List<String> redirectChain, int attempts) {
        return new ArticleFetchResult(requestedUrl, finalUrl, ArticleFetchOutcome.FETCHED, fetchedAt,
                httpStatus, contentType, rawHtmlRef, contentLength, redirectChain, attempts, null);
    }

    public static ArticleFetchResult skippedNotHtml(String requestedUrl, String finalUrl, Instant fetchedAt,
                                                    Integer httpStatus, String contentType, String reason,
                                                    List<String> redirectChain, int attempts) {
        return new ArticleFetchResult(requestedUrl, finalUrl, ArticleFetchOutcome.SKIPPED_NOT_HTML, fetchedAt,
                httpStatus, contentType, null, 0, redirectChain, attempts, reason);
    }

    public static ArticleFetchResult blockedByRobots(String requestedUrl, String finalUrl, Instant fetchedAt,
                                                     String reason, List<String> redirectChain) {
        return new ArticleFetchResult(requestedUrl, finalUrl, ArticleFetchOutcome.BLOCKED_BY_ROBOTS, fetchedAt,
                null, null, null, 0, redirectChain, 0, reason);
    }

    public static ArticleFetchResult failed(String requestedUrl, String finalUrl, Instant fetchedAt,
                                            Integer httpStatus, String reason,
                                            List<String> redirectChain, int attempts) {
        return new ArticleFetchResult(requestedUrl, finalUrl, ArticleFetchOutcome.FAILED, fetchedAt,
                httpStatus, null, null, 0, redirectChain, attempts, reason);
    }

    /** True when the chain moved: worth logging, and worth watching for identity drift. */
    public boolean redirected() {
        return redirectChain.size() > 1;
    }
}
