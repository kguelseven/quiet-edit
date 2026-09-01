package org.korhan.quietedit.ingest;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of following one article link.
 *
 * <p>Identity is derived downstream from {@code finalUrl}, not from the URL the feed
 * advertised: publishers routinely publish a short link or a tracking hop, and
 * canonicalising the advertised URL would mint a new document every time the redirect
 * target changes. The whole {@code redirectChain} is kept because "which hop rewrote the
 * URL" is the only way to diagnose an identity that drifted.
 *
 * <p>No HTML is carried, only {@code rawHtmlRef}: a run fetches hundreds of articles
 * concurrently, and holding every body until it finishes would make peak memory a
 * function of feed size.
 *
 * <p>Nothing is parsed here, so the page title and the article text are absent by design.
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
