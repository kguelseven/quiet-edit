package org.korhan.quietedit.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Result of one {@code runOnce()}: what the catalogue said, and how every feed answered. */
public record FeedFetchRun(
        Instant startedAt,
        Instant finishedAt,
        FeedCatalogService.CatalogSync catalog,
        List<FeedFetchResult> results) {

    public FeedFetchRun {
        results = List.copyOf(results);
    }

    public Duration duration() {
        return Duration.between(startedAt, finishedAt);
    }

    public long count(FeedFetchOutcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
    }
}
