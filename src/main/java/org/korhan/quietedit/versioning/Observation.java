package org.korhan.quietedit.versioning;

import org.korhan.quietedit.ingest.ArticleContent;

import java.time.Instant;
import java.util.Objects;

/**
 * One look at an article, in the shape the version store needs to write it down.
 *
 * <p>Carries no content hash: the hash decides whether this observation becomes a
 * revision at all, so letting a caller supply one would let a caller decide, and two
 * callers hashing slightly differently would split one document's history in two.
 *
 * <p>{@code publishedAtExact} says whether the date was read verbatim or inferred. Both
 * fields come from date normalisation, which owns every assumption behind them; the store
 * writes them down and forms no opinion.
 *
 * <p>Only the publication date travels here: a feed's {@code updated} date is a statement
 * about the publisher's record-keeping, and this system decides that a document changed
 * by comparing the text it fetched.
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
