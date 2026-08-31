package org.korhan.quietedit.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * The result of one {@code runOnce()}: what the catalogue said, how every feed
 * answered, and what became of every article those feeds advertised.
 *
 * <p>Counts are derived rather than stored so that they cannot drift from the
 * per-feed detail they summarise.
 *
 * <p>{@code rechecks} is the run's second source of articles: documents the re-check
 * policy offered that no feed advertised any more. They are kept apart from
 * {@code feeds} because they belong to no feed's answer -- a feed's entry count and
 * its article list have to keep adding up -- but they are articles of the same run,
 * so {@link #articles()} and every count over it include them.
 */
public record IngestRun(
        Instant startedAt,
        Instant finishedAt,
        FeedCatalogService.CatalogSync catalog,
        List<FeedIngestResult> feeds,
        List<ArticleIngestResult> rechecks) {

    public IngestRun {
        feeds = List.copyOf(feeds);
        rechecks = List.copyOf(rechecks);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public long feedCount(FeedFetchOutcome outcome) {
        return feeds.stream().filter(feed -> feed.fetchOutcome() == outcome).count();
    }

    public List<ArticleIngestResult> articles() {
        return Stream.concat(feeds.stream().flatMap(feed -> feed.articles().stream()), rechecks.stream())
                .toList();
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
