package org.korhan.quietedit.versioning;

import org.korhan.quietedit.ingest.ArticleContent;

import java.time.Instant;
import java.util.Objects;

/**
 * One look at an article, in the shape the version store needs to write it down.
 *
 * <p>Carries no content hash. The hash is what decides whether this observation
 * becomes a revision at all, so letting a caller supply one would let a caller
 * decide -- and two callers hashing slightly differently would split one document's
 * history into two. {@link VersionStore} computes it from {@code content}.
 *
 * <p>{@code publishedAt} is nullable and {@code publishedAtExact} says whether it
 * was read verbatim or inferred. Both come from date normalisation in ingest, which
 * owns every assumption behind them -- an assumed timezone, a bare date, a date so
 * far in the future that the retrieval time replaced it. The store writes them down
 * and forms no opinion of its own.
 *
 * <p>Only the publication date travels here. A feed's {@code updated} date is a
 * statement about the publisher's own record-keeping, and this system decides that
 * a document changed by comparing the text it fetched, never by believing a claim
 * in the feed.
 */
public record Observation(
        Instant fetchedAt,
        ArticleContent content,
        int httpStatus,
        String feedTitle,
        String rawHtmlRef,
        Instant publishedAt,
        boolean publishedAtExact,
        EncodingVerdict encoding) {

    public Observation {
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        Objects.requireNonNull(content, "content");
        if (publishedAt == null && publishedAtExact) {
            throw new IllegalArgumentException("A publication date that is absent cannot be exact");
        }
    }
}
