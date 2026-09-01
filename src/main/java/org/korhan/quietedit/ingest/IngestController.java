package org.korhan.quietedit.ingest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Triggers a full ingest run on demand: the same {@link IngestService#runOnce()} the
 * scheduler calls, so an operator needs no code path of its own.
 *
 * <p>The response mirrors the run's result object rather than exposing it: a
 * {@code FeedFetchResult} carries the feed body, and an entity would drag its JPA shape
 * into the API.
 *
 * <p>Synchronous on purpose. A run takes minutes, which makes this an operator's endpoint;
 * turning it into a job with a status resource is a decision the API ticket owns.
 *
 * <p>A trigger colliding with a run in flight is refused by the service and rendered by
 * {@link IngestExceptionHandler}; no branch for it exists here.
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
            List<FeedItem> feeds,
            /* Articles the re-check policy offered that no feed advertises any more.
             * Kept beside the feeds rather than inside one, because they belong to
             * no feed's answer -- see IngestRun. */
            List<ArticleItem> rechecks) {

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
                            run.count(ArticleIngestOutcome.CHANGED),
                            run.count(ArticleIngestOutcome.UNCHANGED),
                            run.count(ArticleIngestOutcome.SKIPPED),
                            run.count(ArticleIngestOutcome.FAILED),
                            run.count(ArticleIngestOutcome.DEFERRED),
                            run.count(ArticleIngestOutcome.ABANDONED),
                            run.count(ArticleIngestOutcome.NOT_DUE)),
                    run.feeds().stream().map(FeedItem::of).toList(),
                    run.rechecks().stream().map(ArticleItem::of).toList());
        }
    }

    public record CatalogSummary(int listed, int added, int renamed) {
    }

    public record FeedSummary(int polled, long fetched, long notModified, long failed) {
    }

    /**
     * {@code checked} is the sum of the outcome counts. Subtracting {@code deferred},
     * {@code abandoned} and {@code notDue} from it is what the run actually fetched.
     */
    public record ArticleSummary(int checked, long created, long changed, long unchanged, long skipped,
                                 long failed, long deferred, long abandoned, long notDue) {
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
            UUID versionId,
            int versionNumber,
            int paragraphs,
            String encoding,
            String reason) {

        /**
         * {@code encoding} is prose rather than three fields: over REST it is read by a
         * person deciding whether a page needs a look, and null for every article that
         * never got a body.
         */
        static ArticleItem of(ArticleIngestResult article) {
            return new ArticleItem(article.link(), article.finalUrl(), article.canonicalUrl(),
                    article.outcome(), article.documentId(), article.versionId(), article.versionNumber(),
                    article.paragraphs(),
                    article.encoding() == null ? null : article.encoding().describe(), article.reason());
        }
    }
}
