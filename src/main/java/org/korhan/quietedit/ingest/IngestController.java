package org.korhan.quietedit.ingest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Triggers a full ingest run on demand: the same {@link IngestService#runOnce()}
 * the scheduler calls, so an operator can run a poll without waiting for the timer
 * and without a code path of its own.
 *
 * <p>The response mirrors the run's result object rather than exposing it directly.
 * A {@code FeedFetchResult} carries the feed body, and an entity would drag its
 * JPA shape into the API; the records here carry counts, URLs and reasons -- enough
 * to explain a run, never its content.
 *
 * <p>Synchronous on purpose: a run over a real catalogue takes minutes, which makes
 * this a operator's endpoint rather than a public one. Turning it into a job with a
 * status resource is a decision the API ticket owns, not this one.
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestService service;

    public IngestController(IngestService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public RunResponse run() {
        return RunResponse.of(service.runOnce());
    }

    public record RunResponse(
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            CatalogSummary catalog,
            FeedSummary feedSummary,
            ArticleSummary articleSummary,
            List<FeedItem> feeds) {

        static RunResponse of(IngestRun run) {
            return new RunResponse(
                    run.startedAt(),
                    run.finishedAt(),
                    run.duration().toMillis(),
                    new CatalogSummary(run.catalog().listed(), run.catalog().added(), run.catalog().renamed()),
                    new FeedSummary(
                            run.feeds().size(),
                            run.feedCount(FeedFetchOutcome.FETCHED),
                            run.feedCount(FeedFetchOutcome.NOT_MODIFIED),
                            run.feedCount(FeedFetchOutcome.FAILED)),
                    new ArticleSummary(
                            run.checked(),
                            run.count(ArticleIngestOutcome.NEW),
                            run.count(ArticleIngestOutcome.UNCHANGED),
                            run.count(ArticleIngestOutcome.SKIPPED),
                            run.count(ArticleIngestOutcome.FAILED)),
                    run.feeds().stream().map(FeedItem::of).toList());
        }
    }

    public record CatalogSummary(int listed, int added, int renamed) {
    }

    public record FeedSummary(int polled, long fetched, long notModified, long failed) {
    }

    /** The ticket's four counts. {@code checked} is their sum. */
    public record ArticleSummary(int checked, long created, long unchanged, long skipped, long failed) {
    }

    public record FeedItem(
            String url,
            FeedFetchOutcome outcome,
            Integer httpStatus,
            String feedType,
            int entries,
            int skippedEntries,
            String failureReason,
            List<ArticleItem> articles) {

        static FeedItem of(FeedIngestResult feed) {
            return new FeedItem(feed.url(), feed.fetchOutcome(), feed.httpStatus(), feed.feedType(),
                    feed.entries(), feed.skippedEntries(), feed.failureReason(),
                    feed.articles().stream().map(ArticleItem::of).toList());
        }
    }

    public record ArticleItem(
            String link,
            String finalUrl,
            String canonicalUrl,
            ArticleIngestOutcome outcome,
            UUID documentId,
            int paragraphs,
            String reason) {

        static ArticleItem of(ArticleIngestResult article) {
            return new ArticleItem(article.link(), article.finalUrl(), article.canonicalUrl(),
                    article.outcome(), article.documentId(), article.paragraphs(), article.reason());
        }
    }
}
