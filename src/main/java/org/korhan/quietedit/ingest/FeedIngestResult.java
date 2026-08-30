package org.korhan.quietedit.ingest;

import java.util.List;

/**
 * One feed's whole contribution to a run: how the request ended, what the body
 * parsed into, and what became of every article it advertised.
 *
 * <p>{@code failureReason} covers both the fetch and the parse, because from the
 * run's point of view they are the same event -- this feed produced no entries, and
 * here is why. Which stage failed is still readable from {@code fetchOutcome}.
 *
 * <p>A feed with no articles is normal, not an error: a 304 means nothing changed,
 * and a feed can legitimately be empty.
 */
public record FeedIngestResult(
        String url,
        FeedFetchOutcome fetchOutcome,
        Integer httpStatus,
        String feedType,
        int entries,
        int skippedEntries,
        String failureReason,
        List<ArticleIngestResult> articles) {

    public FeedIngestResult {
        articles = List.copyOf(articles);
    }

    static FeedIngestResult withoutEntries(FeedFetchResult fetch, String failureReason) {
        return new FeedIngestResult(fetch.url(), fetch.outcome(), fetch.httpStatus(),
                null, 0, 0, failureReason, List.of());
    }

    static FeedIngestResult parsed(FeedFetchResult fetch, FeedParseResult parse,
                                   List<ArticleIngestResult> articles) {
        return new FeedIngestResult(fetch.url(), fetch.outcome(), fetch.httpStatus(),
                parse.feedType(), parse.entries().size(), parse.skipped().size(), null, articles);
    }
}
