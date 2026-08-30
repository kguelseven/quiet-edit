package org.korhan.quietedit.ingest;

import org.korhan.quietedit.versioning.DocumentRegistry;
import org.korhan.quietedit.versioning.UrlCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The whole path from outside to inside, in one synchronous call: sync the feed
 * catalogue, poll every feed, parse what came back, follow every article link,
 * strip the boilerplate, and resolve each article to the document it belongs to.
 *
 * <p>The one entry point on purpose. The scheduler and the REST endpoint both call
 * {@link #runOnce()} and neither holds a decision of its own, which is what makes a
 * full run testable without a scheduler and triggerable by hand in production.
 *
 * <p>Nothing is versioned here. A run records <em>that</em> an article was seen and
 * under which identity; whether its text moved since the last observation needs a
 * content hash and the version store, and those are separate tickets. The result
 * object's {@code NEW}/{@code UNCHANGED} split is therefore about identity, not
 * content -- see {@link ArticleIngestOutcome}.
 *
 * <p>Failure isolation is the load-bearing property. Every stage turns its expected
 * failures into results rather than exceptions, and each stage is additionally
 * wrapped here, so one publisher serving a 500, a body that is not a feed, an
 * article behind a paywall or an outright defect costs exactly that one feed or that
 * one article -- never the run.
 *
 * <p>Not {@code @Transactional}: a run spends nearly all of its wall clock waiting
 * on remote servers, and a transaction spanning it would pin a connection for the
 * whole poll. Writes happen in short transactions per feed row and per document.
 *
 * <p>One run at a time. The scheduler cannot overlap itself, but {@code POST
 * /api/ingest/run} can fire into a scheduled run and two operators can trigger it
 * together; both runs would then fetch the same articles and race each other on the
 * unique constraint of {@code document.canonical_url}, which the loser would report
 * as a failed article rather than as the duplicate it is. A second attempt is
 * therefore refused outright rather than queued: a queued run would only repeat
 * work that the run in flight is already doing. The guard is one process's -- a
 * second instance of the application would need a lock in the database, which is a
 * decision for the day this is deployed more than once.
 *
 * <p>A run is also bounded: {@link ArticleBudget} caps how many articles it may
 * fetch and decides which candidates get that budget. Everything above the cap is
 * reported as {@link ArticleIngestOutcome#DEFERRED} and picked up by the next run.
 * Every attempt is written back to {@link ArticleAttemptLog}, which is what lets the
 * budget rank by "last tried" rather than by "last succeeded" and eventually stop
 * offering a link that never works.
 *
 * <p>Articles are fetched on virtual threads, and the fan-out covers <em>all</em>
 * feeds' links at once rather than one feed at a time: {@link HostRateLimiter}
 * already serialises per host, so a wide fan-out only ever parallelises across
 * distinct hosts, and going feed by feed would idle every other publisher while one
 * host works through its own queue. Documents are then registered serially in the
 * calling thread, which -- together with link de-duplication -- means two threads
 * can never race to create the same document.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final FeedFetchService feedFetchService;
    private final FeedParser feedParser;
    private final ArticleFetcher articleFetcher;
    private final ArticleExtractor articleExtractor;
    private final RawHtmlStore rawHtml;
    private final UrlCanonicalizer canonicalizer;
    private final DocumentRegistry documents;
    private final ArticleAttemptLog attemptLog;
    private final Clock clock;
    private final ArticleBudget budget;

    /** False between runs. The single permit that keeps two runs from overlapping. */
    private final AtomicBoolean running = new AtomicBoolean();

    public IngestService(FeedFetchService feedFetchService, FeedParser feedParser,
                         ArticleFetcher articleFetcher, ArticleExtractor articleExtractor,
                         RawHtmlStore rawHtml, UrlCanonicalizer canonicalizer,
                         DocumentRegistry documents, ArticleAttemptLog attemptLog, Clock clock,
                         IngestRunProperties properties) {
        this.feedFetchService = feedFetchService;
        this.feedParser = feedParser;
        this.articleFetcher = articleFetcher;
        this.articleExtractor = articleExtractor;
        this.rawHtml = rawHtml;
        this.canonicalizer = canonicalizer;
        this.documents = documents;
        this.attemptLog = attemptLog;
        this.clock = clock;
        this.budget = new ArticleBudget(properties.maxArticles(), properties.maxArticleFailures());
    }

    /**
     * @throws IngestAlreadyRunningException if a run is already in flight
     */
    public IngestRun runOnce() {
        if (!running.compareAndSet(false, true)) {
            throw new IngestAlreadyRunningException();
        }
        try {
            return run();
        } finally {
            running.set(false);
        }
    }

    private IngestRun run() {
        Instant startedAt = clock.instant();
        FeedFetchRun feedRun = feedFetchService.runOnce();

        Set<String> seenLinks = new LinkedHashSet<>();
        List<FeedWork> work = feedRun.results().stream().map(fetch -> plan(fetch, seenLinks)).toList();
        List<Candidate> candidates = work.stream().flatMap(feed -> feed.candidates.stream()).toList();
        for (Attempt attempt : fetchAll(admit(candidates))) {
            Candidate candidate = attempt.candidate();
            ArticleIngestResult result = resolve(attempt);
            candidate.work().articles[candidate.slot()] = result;
            recordAttempt(candidate, result);
        }

        IngestRun run = new IngestRun(startedAt, clock.instant(), feedRun.catalog(),
                work.stream().map(FeedWork::toResult).toList());
        log.info("Ingest run finished in {}: feeds {} fetched / {} unchanged / {} failed; "
                        + "articles {} planned, {} new, {} unchanged, {} skipped, {} failed, "
                        + "{} deferred, {} abandoned",
                run.duration(),
                run.feedCount(FeedFetchOutcome.FETCHED), run.feedCount(FeedFetchOutcome.NOT_MODIFIED),
                run.feedCount(FeedFetchOutcome.FAILED),
                run.checked(), run.count(ArticleIngestOutcome.NEW), run.count(ArticleIngestOutcome.UNCHANGED),
                run.count(ArticleIngestOutcome.SKIPPED), run.count(ArticleIngestOutcome.FAILED),
                run.count(ArticleIngestOutcome.DEFERRED), run.count(ArticleIngestOutcome.ABANDONED));
        return run;
    }

    /**
     * Applies the run's ceiling and its give-up rule, deciding every candidate the
     * run will not fetch.
     *
     * <p>The attempt log is consulted even when the run fits inside its ceiling.
     * Ranking would indeed be pointless then, but abandonment is not: a catalogue
     * small enough to fit would otherwise re-fetch a permanently broken link on every
     * single poll, which is the cost this rule exists to stop.
     */
    private List<Candidate> admit(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        Map<String, AttemptHistory> history = attemptLog.historyOf(
                candidates.stream().map(Candidate::identity).collect(Collectors.toSet()));
        ArticleBudget.Selection selection = budget.admit(
                candidates.stream().map(candidate -> history.get(candidate.identity())).toList());

        List<Candidate> selected = new ArrayList<>(selection.admitted().size());
        for (int position = 0; position < candidates.size(); position++) {
            Candidate candidate = candidates.get(position);
            if (selection.admitted().contains(position)) {
                selected.add(candidate);
            } else if (selection.abandoned().contains(position)) {
                candidate.work().articles[candidate.slot()] = ArticleIngestResult.abandoned(
                        candidate.link(), history.get(candidate.identity()).failureCount());
            } else {
                candidate.work().articles[candidate.slot()] = ArticleIngestResult.deferred(candidate.link());
            }
        }
        if (!selection.abandoned().isEmpty()) {
            log.warn("{} of {} candidates were abandoned after {} consecutive failed attempts",
                    selection.abandoned().size(), candidates.size(), budget.maxFailures());
        }
        int deferred = candidates.size() - selected.size() - selection.abandoned().size();
        if (deferred > 0) {
            log.warn("Article budget of {} reached: {} of {} candidates deferred to the next run",
                    budget.maxArticles(), deferred, candidates.size());
        }
        return selected;
    }

    /**
     * Writes back that this link was tried and whether the try produced a document,
     * which is what moves it out of the front of the next run's queue and, after
     * enough consecutive failures, out of the candidate set entirely.
     *
     * <p>A document id is the success test rather than the outcome enum: it is the
     * one thing that means "this link resolved to an article", however the run chose
     * to label it.
     *
     * <p>Contained like every other per-article step. A log that cannot be written is
     * a lost strike, not a lost article.
     */
    private void recordAttempt(Candidate candidate, ArticleIngestResult result) {
        try {
            attemptLog.record(candidate.identity(), clock.instant(), result.documentId() != null);
        } catch (RuntimeException e) {
            log.error("Could not record the attempt on {}", candidate.identity(), e);
        }
    }

    /**
     * The identity a link would have if it resolved without redirects and without a
     * {@code rel=canonical} of its own. Good enough to rank by -- a link whose real
     * identity differs is at worst ranked as unseen, which only costs it a place at
     * the front of the queue -- and it is the only identity available before the
     * fetch, which is the whole point of ranking here.
     *
     * <p>A link that cannot be canonicalised at all falls back to the raw link as its
     * own identity rather than staying unidentified. It is still fetched, so that the
     * fetch stage is the one that reports it broken, but it now accumulates strikes
     * like any other failing link -- an untracked link would rank as never-tried
     * forever and starve the catalogue behind it, which is precisely the failure this
     * whole mechanism exists to prevent.
     */
    private String provisionalIdentity(String link) {
        try {
            return canonicalizer.canonicalize(link);
        } catch (RuntimeException e) {
            return link;
        }
    }

    /**
     * Parses one feed body into the links a run will follow. A 304 or a failed fetch
     * carries no body and is not an error at this stage -- it simply contributes no
     * entries.
     */
    private FeedWork plan(FeedFetchResult fetch, Set<String> seenLinks) {
        if (fetch.outcome() != FeedFetchOutcome.FETCHED) {
            return FeedWork.withoutEntries(fetch, fetch.failureReason());
        }
        FeedParseResult parse;
        try {
            String body = EncodingResolver.decode(fetch.body(), fetch.contentType(), fetch.url());
            parse = feedParser.parse(fetch.url(), body);
        } catch (RuntimeException e) {
            log.error("Unexpected failure while parsing {}", fetch.url(), e);
            return FeedWork.withoutEntries(fetch, "unexpected: " + e.getClass().getSimpleName());
        }
        if (parse.failed()) {
            log.info("Feed {} yielded no entries: {}", fetch.url(), parse.failureReason());
            return FeedWork.withoutEntries(fetch, parse.failureReason());
        }
        if (!parse.skipped().isEmpty()) {
            log.info("Feed {}: {} entries skipped by the parser", fetch.url(), parse.skipped().size());
        }
        return FeedWork.parsed(fetch, parse, seenLinks, this::provisionalIdentity);
    }

    private List<Attempt> fetchAll(List<Candidate> candidates) {
        List<Attempt> attempts = new ArrayList<>(candidates.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Attempt>> futures = candidates.stream()
                    .map(candidate -> executor.submit(() -> attempt(candidate)))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                attempts.add(await(candidates.get(i), futures.get(i)));
            }
        }
        return attempts;
    }

    private Attempt await(Candidate candidate, Future<Attempt> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.decided(candidate, ArticleIngestResult.failed(candidate.link(), null, "interrupted"));
        } catch (ExecutionException e) {
            // attempt() contains its own failures, so this is a defect -- contained per article.
            log.error("Unexpected failure while ingesting {}", candidate.link(), e.getCause());
            return Attempt.decided(candidate, ArticleIngestResult.failed(candidate.link(), null,
                    "unexpected: " + e.getCause().getClass().getSimpleName()));
        }
    }

    /** Network and parsing for one article. Touches no database, so it is safe to fan out. */
    private Attempt attempt(Candidate candidate) {
        ArticleFetchResult fetch = articleFetcher.fetch(candidate.link());
        return switch (fetch.outcome()) {
            case BLOCKED_BY_ROBOTS, SKIPPED_NOT_HTML -> Attempt.decided(candidate,
                    ArticleIngestResult.skipped(candidate.link(), fetch.finalUrl(), fetch.failureReason()));
            case FAILED -> Attempt.decided(candidate,
                    ArticleIngestResult.failed(candidate.link(), fetch.finalUrl(), fetch.failureReason()));
            case FETCHED -> read(candidate, fetch);
        };
    }

    /**
     * The HTML is read back from the store rather than carried through the fetch
     * result: a run has hundreds of articles in flight, and only the one being
     * extracted belongs in memory.
     */
    private Attempt read(Candidate candidate, ArticleFetchResult fetch) {
        String html;
        try {
            html = EncodingResolver.decode(rawHtml.read(fetch.rawHtmlRef()), fetch.contentType(), fetch.finalUrl());
        } catch (RuntimeException e) {
            return Attempt.decided(candidate, ArticleIngestResult.failed(
                    candidate.link(), fetch.finalUrl(), "raw html unreadable: " + e.getMessage()));
        }
        ArticleContent content = articleExtractor.extract(html);
        if (content.isEmpty()) {
            return Attempt.decided(candidate, ArticleIngestResult.skipped(
                    candidate.link(), fetch.finalUrl(), "no extractable content"));
        }
        String canonicalUrl;
        try {
            canonicalUrl = canonicalizer.canonicalize(fetch.finalUrl(), html);
        } catch (RuntimeException e) {
            return Attempt.decided(candidate, ArticleIngestResult.failed(
                    candidate.link(), fetch.finalUrl(), "unusable url: " + e.getMessage()));
        }
        return new Attempt(candidate, fetch, canonicalUrl, content, null);
    }

    /** The only stage that writes documents, and it runs single-threaded. */
    private ArticleIngestResult resolve(Attempt attempt) {
        if (attempt.decided() != null) {
            return attempt.decided();
        }
        Candidate candidate = attempt.candidate();
        ArticleFetchResult fetch = attempt.fetch();
        try {
            DocumentRegistry.Registration registration =
                    documents.register(attempt.canonicalUrl(), candidate.feedId(), fetch.fetchedAt());
            return ArticleIngestResult.ingested(candidate.link(), fetch.finalUrl(), attempt.canonicalUrl(),
                    registration.created(), registration.documentId(), fetch.rawHtmlRef(),
                    attempt.content().paragraphs().size());
        } catch (RuntimeException e) {
            log.error("Could not register {}", attempt.canonicalUrl(), e);
            return ArticleIngestResult.failed(candidate.link(), fetch.finalUrl(),
                    "not registered: " + e.getClass().getSimpleName());
        }
    }

    /**
     * One feed's slot table. Every parsed entry keeps its position, so a run's report
     * lists articles in feed order however the concurrent fetches happened to finish.
     */
    private static final class FeedWork {

        private final FeedFetchResult fetch;
        private final FeedParseResult parse;
        private final String failureReason;
        private final ArticleIngestResult[] articles;
        private final List<Candidate> candidates = new ArrayList<>();

        private FeedWork(FeedFetchResult fetch, FeedParseResult parse, String failureReason, int slots) {
            this.fetch = fetch;
            this.parse = parse;
            this.failureReason = failureReason;
            this.articles = new ArticleIngestResult[slots];
        }

        static FeedWork withoutEntries(FeedFetchResult fetch, String failureReason) {
            return new FeedWork(fetch, null, failureReason, 0);
        }

        /**
         * A link advertised twice -- by two feeds of one publisher, or twice within
         * one feed -- is fetched once; {@code seenLinks} is therefore the whole
         * run's, not this feed's. Without that, the duplicate would be fetched a
         * second time only to be recognised as the same document, and the second
         * fetch would additionally race the first for its creation. Which feed wins
         * a shared link is decided by feed order, which is itself stable.
         */
        static FeedWork parsed(FeedFetchResult fetch, FeedParseResult parse, Set<String> seenLinks,
                               UnaryOperator<String> identity) {
            FeedWork work = new FeedWork(fetch, parse, null, parse.entries().size());
            for (int slot = 0; slot < parse.entries().size(); slot++) {
                String link = parse.entries().get(slot).link().trim();
                if (seenLinks.add(link)) {
                    work.candidates.add(new Candidate(work, slot, fetch.feedId(), link, identity.apply(link)));
                } else {
                    work.articles[slot] = ArticleIngestResult.skipped(link, null, "duplicate link in this run");
                }
            }
            return work;
        }

        FeedIngestResult toResult() {
            if (parse == null) {
                return FeedIngestResult.withoutEntries(fetch, failureReason);
            }
            return FeedIngestResult.parsed(fetch, parse,
                    Arrays.stream(articles).filter(Objects::nonNull).toList());
        }
    }

    /**
     * @param identity what the budget and the attempt log key this link by, resolved
     *                 before the fetch because the ranking happens before the fetch
     */
    private record Candidate(FeedWork work, int slot, UUID feedId, String link, String identity) {
    }

    /**
     * One article after the network stage. Either {@code decided} is set -- the
     * article needs no document -- or the content and canonical URL are.
     */
    private record Attempt(Candidate candidate, ArticleFetchResult fetch, String canonicalUrl,
                           ArticleContent content, ArticleIngestResult decided) {

        static Attempt decided(Candidate candidate, ArticleIngestResult result) {
            return new Attempt(candidate, null, null, null, result);
        }
    }
}
