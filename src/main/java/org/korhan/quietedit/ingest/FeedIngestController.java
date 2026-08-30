package org.korhan.quietedit.ingest;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Triggers a feed poll on demand. The same {@code runOnce()} a scheduler would
 * call, so the endpoint is the test seam for the whole run.
 */
@RestController
@RequestMapping("/api/ingest/feeds")
public class FeedIngestController {

    private final FeedFetchService service;

    public FeedIngestController(FeedFetchService service) {
        this.service = service;
    }

    @PostMapping("/run")
    public RunResponse run() {
        return RunResponse.of(service.runOnce());
    }

    /** Response bodies report sizes, never the fetched feeds themselves. */
    public record RunResponse(
            Instant startedAt,
            Instant finishedAt,
            long durationMillis,
            int listedInCatalog,
            int addedToCatalog,
            long fetched,
            long notModified,
            long failed,
            List<FeedRunItem> feeds) {

        static RunResponse of(FeedFetchRun run) {
            return new RunResponse(
                    run.startedAt(),
                    run.finishedAt(),
                    run.duration().toMillis(),
                    run.catalog().listed(),
                    run.catalog().added(),
                    run.count(FeedFetchOutcome.FETCHED),
                    run.count(FeedFetchOutcome.NOT_MODIFIED),
                    run.count(FeedFetchOutcome.FAILED),
                    run.results().stream().map(FeedRunItem::of).toList());
        }
    }

    public record FeedRunItem(
            String url,
            FeedFetchOutcome outcome,
            Integer httpStatus,
            int bodySize,
            int attempts,
            String failureReason) {

        static FeedRunItem of(FeedFetchResult result) {
            return new FeedRunItem(result.url(), result.outcome(), result.httpStatus(),
                    result.bodySize(), result.attempts(), result.failureReason());
        }
    }
}
