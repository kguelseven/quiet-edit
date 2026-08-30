package org.korhan.quietedit.ingest;

import org.korhan.quietedit.versioning.EncodingVerdict;

import java.util.UUID;

/**
 * What a run did with one article link.
 *
 * <p>Three URLs, because all three are needed to explain an identity: {@code link}
 * is what the feed advertised, {@code finalUrl} is where the redirect chain ended,
 * and {@code canonicalUrl} is what the document is keyed by. When a document turns
 * up under an unexpected identity, the difference between these three is the whole
 * diagnosis.
 *
 * <p>{@code paragraphs} is a count, not the prose: a run's result object is a
 * report, and reports that carry article bodies cannot be logged or returned over
 * REST. The text itself is reachable through {@code rawHtmlRef}.
 *
 * <p>{@code encoding} is how the markup was decoded, and it is null for every
 * outcome that never got a body -- deferred, abandoned, blocked, failed before the
 * response. Carried here rather than left in a log line because it is the only place
 * the verdict can reach the version store from: whoever writes a version reads this
 * result, and mojibake that arrives unlabelled is indistinguishable from prose.
 */
public record ArticleIngestResult(
        String link,
        String finalUrl,
        String canonicalUrl,
        ArticleIngestOutcome outcome,
        UUID documentId,
        String rawHtmlRef,
        int paragraphs,
        EncodingVerdict encoding,
        String reason) {

    static ArticleIngestResult ingested(String link, String finalUrl, String canonicalUrl, boolean created,
                                        UUID documentId, String rawHtmlRef, int paragraphs,
                                        EncodingVerdict encoding) {
        return new ArticleIngestResult(link, finalUrl, canonicalUrl,
                created ? ArticleIngestOutcome.NEW : ArticleIngestOutcome.UNCHANGED,
                documentId, rawHtmlRef, paragraphs, encoding, null);
    }

    static ArticleIngestResult skipped(String link, String finalUrl, String reason) {
        return new ArticleIngestResult(link, finalUrl, null, ArticleIngestOutcome.SKIPPED,
                null, null, 0, null, reason);
    }

    /**
     * No URL beyond the advertised link, because a deferred candidate was never
     * fetched: there is no redirect chain and no page to canonicalise yet.
     */
    static ArticleIngestResult deferred(String link) {
        return new ArticleIngestResult(link, null, null, ArticleIngestOutcome.DEFERRED,
                null, null, 0, null, "deferred by the run's article budget");
    }

    /**
     * Also no URL beyond the link: an abandoned candidate is not fetched either. The
     * strike count is spelled out in the reason because it is the whole justification
     * for the run declining to try.
     */
    static ArticleIngestResult abandoned(String link, int failureCount) {
        return new ArticleIngestResult(link, null, null, ArticleIngestOutcome.ABANDONED,
                null, null, 0, null, "abandoned after " + failureCount + " consecutive failed attempts");
    }

    static ArticleIngestResult failed(String link, String finalUrl, String reason) {
        return new ArticleIngestResult(link, finalUrl, null, ArticleIngestOutcome.FAILED,
                null, null, 0, null, reason);
    }
}
