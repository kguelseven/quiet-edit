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
 * was read verbatim or inferred. Ingest passes null for both today: the feed's date
 * text is still unparsed, and turning it into an instant is date normalisation's
 * decision, not this store's.
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
    }

    /** What ingest knows today: no publication date, because nothing has parsed one yet. */
    public static Observation of(Instant fetchedAt, ArticleContent content, int httpStatus,
                                 String feedTitle, String rawHtmlRef, EncodingVerdict encoding) {
        return new Observation(fetchedAt, content, httpStatus, feedTitle, rawHtmlRef, null, true, encoding);
    }
}
