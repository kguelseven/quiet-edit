package org.korhan.quietedit.ingest;

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
 */
public record ArticleIngestResult(
        String link,
        String finalUrl,
        String canonicalUrl,
        ArticleIngestOutcome outcome,
        UUID documentId,
        String rawHtmlRef,
        int paragraphs,
        String reason) {

    static ArticleIngestResult ingested(String link, String finalUrl, String canonicalUrl, boolean created,
                                        UUID documentId, String rawHtmlRef, int paragraphs) {
        return new ArticleIngestResult(link, finalUrl, canonicalUrl,
                created ? ArticleIngestOutcome.NEW : ArticleIngestOutcome.UNCHANGED,
                documentId, rawHtmlRef, paragraphs, null);
    }

    static ArticleIngestResult skipped(String link, String finalUrl, String reason) {
        return new ArticleIngestResult(link, finalUrl, null, ArticleIngestOutcome.SKIPPED,
                null, null, 0, reason);
    }

    static ArticleIngestResult failed(String link, String finalUrl, String reason) {
        return new ArticleIngestResult(link, finalUrl, null, ArticleIngestOutcome.FAILED,
                null, null, 0, reason);
    }
}
