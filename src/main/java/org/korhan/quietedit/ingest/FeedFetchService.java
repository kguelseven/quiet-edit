package org.korhan.quietedit.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The one synchronous entry point for a feed poll, reachable over REST and callable from
 * a scheduler, so that neither caller holds logic.
 *
 * <p>Feeds are fetched on virtual threads because a run is almost entirely waiting on
 * remote servers; {@link HostRateLimiter} guards the gate, so the fan-out only ever
 * parallelises across distinct hosts.
 *
 * <p>Deliberately not {@code @Transactional}: a transaction spanning the network I/O
 * would pin a connection for the whole poll. Each feed's state is written in its own
 * short transaction, so a failure while writing one cannot roll back the others.
 */
@Service
public class FeedFetchService {

    private static final Logger log = LoggerFactory.getLogger(FeedFetchService.class);

    private final FeedRepository feeds;
    private final FeedFetcher fetcher;
    private final FeedCatalogService catalog;
    private final Clock clock;

    public FeedFetchService(FeedRepository feeds, FeedFetcher fetcher, FeedCatalogService catalog, Clock clock) {
        this.feeds = feeds;
        this.fetcher = fetcher;
        this.catalog = catalog;
        this.clock = clock;
    }

    public FeedFetchRun runOnce() {
        Instant startedAt = clock.instant();
        FeedCatalogService.CatalogSync sync = catalog.sync();
        List<Feed> active = feeds.findByActiveTrueOrderByUrlAsc();

        List<FeedFetchResult> results = fetchAll(active);
        for (int i = 0; i < active.size(); i++) {
            record(active.get(i), results.get(i));
        }

        FeedFetchRun run = new FeedFetchRun(startedAt, clock.instant(), sync, results);
        log.info("Feed run finished in {}: {} fetched, {} unchanged, {} failed",
                run.duration(), run.count(FeedFetchOutcome.FETCHED),
                run.count(FeedFetchOutcome.NOT_MODIFIED), run.count(FeedFetchOutcome.FAILED));
        return run;
    }

    private List<FeedFetchResult> fetchAll(List<Feed> active) {
        List<FeedFetchResult> results = new ArrayList<>(active.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<FeedFetchResult>> futures = new ArrayList<>(active.size());
            for (Feed feed : active) {
                futures.add(executor.submit(() -> fetcher.fetch(feed)));
            }
            for (int i = 0; i < futures.size(); i++) {
                results.add(await(active.get(i), futures.get(i)));
            }
        }
        return results;
    }

    /**
     * {@code FeedFetcher} already turns every expected failure into a result, so anything
     * arriving here is a defect -- still contained per feed, because the run must survive it.
     */
    private FeedFetchResult await(Feed feed, Future<FeedFetchResult> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FeedFetchResult.failed(feed, clock.instant(), null, "interrupted", 0);
        } catch (ExecutionException e) {
            log.error("Unexpected failure while fetching {}", feed.getUrl(), e.getCause());
            return FeedFetchResult.failed(feed, clock.instant(), null,
                    "unexpected: " + e.getCause().getClass().getSimpleName(), 0);
        }
    }

    /**
     * Retrieval time and status are always written, so a run is visible even when every
     * feed failed. Validators are only replaced from a 2xx: a 304 that omits them must not
     * clear the ones that produced it, or the next request would pull the whole feed again.
     */
    private void record(Feed feed, FeedFetchResult result) {
        feed.setLastPolledAt(result.fetchedAt());
        feed.setLastStatus(result.httpStatus());
        switch (result.outcome()) {
            case FETCHED -> {
                feed.setEtag(result.etag());
                feed.setLastModified(result.lastModified());
            }
            case NOT_MODIFIED -> {
                if (result.etag() != null) {
                    feed.setEtag(result.etag());
                }
                if (result.lastModified() != null) {
                    feed.setLastModified(result.lastModified());
                }
            }
            case FAILED -> {
                // Validators are kept: they still describe the last body we saw.
            }
        }
        feeds.save(feed);
    }
}
