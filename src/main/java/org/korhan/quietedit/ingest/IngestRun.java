package org.korhan.quietedit.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The result of one {@code runOnce()}: what the catalogue said, how every feed
 * answered, and what became of every article those feeds advertised.
 *
 * <p>Counts are derived rather than stored so that they cannot drift from the
 * per-feed detail they summarise.
 */
public record IngestRun(
        Instant startedAt,
        Instant finishedAt,
        FeedCatalogService.CatalogSync catalog,
        List<FeedIngestResult> feeds) {

    public IngestRun {
        feeds = List.copyOf(feeds);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public long feedCount(FeedFetchOutcome outcome) {
        return feeds.stream().filter(feed -> feed.fetchOutcome() == outcome).count();
    }

    public List<ArticleIngestResult> articles() {
        return feeds.stream().flatMap(feed -> feed.articles().stream()).toList();
    }

    /**
     * Article links the run planned for; the outcome counts add up to this. Not the
     * same as the number of links it fetched -- {@link ArticleIngestOutcome#DEFERRED}
     * ones were left for the next run.
     */
    public int checked() {
        return articles().size();
    }

    public long count(ArticleIngestOutcome outcome) {
        return articles().stream().filter(article -> article.outcome() == outcome).count();
    }
}
