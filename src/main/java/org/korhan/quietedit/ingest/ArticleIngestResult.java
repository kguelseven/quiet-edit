package org.korhan.quietedit.ingest;

import org.korhan.quietedit.versioning.EncodingVerdict;
import org.korhan.quietedit.versioning.VersionStore;

import java.util.UUID;

/**
 * What a run did with one article link.
 *
 * <p>Three URLs, because when a document turns up under an unexpected identity the
 * difference between the advertised link, where the redirect chain ended and what the
 * document is keyed by is the whole diagnosis.
 *
 * <p>{@code paragraphs} is a count, not the prose: a report that carried article bodies
 * could not be logged or returned over REST.
 *
 * <p>{@code versionId} and {@code versionNumber} are null and zero for every outcome that
 * produced no version.
 *
 * <p>{@code encoding} is carried here rather than left in a log line because this result
 * is the only path the verdict has to the version store, and mojibake that arrives
 * unlabelled is indistinguishable from prose.
 */
public record ArticleIngestResult(
        String link,
        String finalUrl,
        String canonicalUrl,
        ArticleIngestOutcome outcome,
        UUID documentId,
        UUID versionId,
        int versionNumber,
        String rawHtmlRef,
        int paragraphs,
        EncodingVerdict encoding,
        String reason) {

    /**
     * A first observation stays {@link ArticleIngestOutcome#NEW} even though it too
     * appended a revision, because "new article" and "edited article" are different events
     * to whoever reads a run.
     *
     * <p>An article returning to a wording it already published reads as
     * {@link ArticleIngestOutcome#CHANGED}: it moved away from what the document said last.
     */
    static ArticleIngestResult ingested(String link, String finalUrl, String canonicalUrl, boolean created,
                                        UUID documentId, VersionStore.Stored stored, String rawHtmlRef,
                                        int paragraphs, EncodingVerdict encoding) {
        ArticleIngestOutcome outcome = created
                ? ArticleIngestOutcome.NEW
                : stored.appended() ? ArticleIngestOutcome.CHANGED : ArticleIngestOutcome.UNCHANGED;
        return new ArticleIngestResult(link, finalUrl, canonicalUrl, outcome, documentId,
                stored.versionId(), stored.versionNumber(), rawHtmlRef, paragraphs, encoding, null);
    }

    static ArticleIngestResult skipped(String link, String finalUrl, String reason) {
        return new ArticleIngestResult(link, finalUrl, null, ArticleIngestOutcome.SKIPPED,
                null, null, 0, null, 0, null, reason);
    }

    /** No URL beyond the link: a deferred candidate was never fetched. */
    static ArticleIngestResult deferred(String link) {
        return new ArticleIngestResult(link, null, null, ArticleIngestOutcome.DEFERRED,
                null, null, 0, null, 0, null, "deferred by the run's article budget");
    }

    /**
     * Also not fetched. The strike count is spelled out in the reason because it is the
     * whole justification for the run declining to try.
     */
    static ArticleIngestResult abandoned(String link, int failureCount) {
        return new ArticleIngestResult(link, null, null, ArticleIngestOutcome.ABANDONED,
                null, null, 0, null, 0, null,
                "abandoned after " + failureCount + " consecutive failed attempts");
    }

    /**
     * The decision is spelled out in the reason rather than left to the outcome: "looked at
     * eight minutes ago" and "stable for a fortnight" both mean no request, but only one
     * means this system has stopped watching the article.
     */
    static ArticleIngestResult notDue(String link, RecheckDecision decision) {
        return new ArticleIngestResult(link, null, null, ArticleIngestOutcome.NOT_DUE,
                null, null, 0, null, 0, null, switch (decision) {
                    case WAITING -> "checked too recently to be due again";
                    case RETIRED -> "retired: nothing observed to change within its observation window";
                    case THROTTLED -> "deferred by the per-host hourly request ceiling";
                    case DUE -> throw new IllegalArgumentException("a due candidate is fetched, not reported");
                });
    }

    static ArticleIngestResult failed(String link, String finalUrl, String reason) {
        return new ArticleIngestResult(link, finalUrl, null, ArticleIngestOutcome.FAILED,
                null, null, 0, null, 0, null, reason);
    }
}
