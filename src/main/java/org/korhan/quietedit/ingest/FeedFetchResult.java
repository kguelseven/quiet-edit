package org.korhan.quietedit.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * The raw outcome of one feed request: response body, retrieval time and HTTP status,
 * plus the validators to send next time.
 *
 * <p>The body stays a {@code byte[]}: a feed declares its encoding in the XML
 * declaration, and decoding before anything has read that declaration would mangle every
 * non-UTF-8 feed. As a consequence record equality is reference-based for the body --
 * compare fields, not whole results.
 *
 * <p>{@code contentType} is kept verbatim rather than reduced to a charset, because
 * encoding resolution needs the header as the server sent it.
 *
 * <p>The body is carried in memory, never persisted; only retrieval time and status are,
 * on the feed row.
 */
public record FeedFetchResult(
        UUID feedId,
        String url,
        FeedFetchOutcome outcome,
        Instant fetchedAt,
        Integer httpStatus,
        String etag,
        String lastModified,
        String contentType,
        byte[] body,
        String failureReason,
        int attempts) {

    public static FeedFetchResult fetched(Feed feed, Instant fetchedAt, int httpStatus, byte[] body,
                                          String etag, String lastModified, String contentType, int attempts) {
        return new FeedFetchResult(feed.getId(), feed.getUrl(), FeedFetchOutcome.FETCHED, fetchedAt,
                httpStatus, etag, lastModified, contentType, body, null, attempts);
    }

    /**
     * A 304 may legitimately carry a refreshed validator, so the headers are kept
     * here too -- the caller updates the feed only where they are present.
     */
    public static FeedFetchResult notModified(Feed feed, Instant fetchedAt, int httpStatus,
                                              String etag, String lastModified, int attempts) {
        return new FeedFetchResult(feed.getId(), feed.getUrl(), FeedFetchOutcome.NOT_MODIFIED, fetchedAt,
                httpStatus, etag, lastModified, null, null, null, attempts);
    }

    public static FeedFetchResult failed(Feed feed, Instant fetchedAt, Integer httpStatus,
                                         String failureReason, int attempts) {
        return new FeedFetchResult(feed.getId(), feed.getUrl(), FeedFetchOutcome.FAILED, fetchedAt,
                httpStatus, null, null, null, null, failureReason, attempts);
    }

    public int bodySize() {
        return body == null ? 0 : body.length;
    }
}
