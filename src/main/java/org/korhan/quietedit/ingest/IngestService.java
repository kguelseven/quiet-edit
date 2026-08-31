package org.korhan.quietedit.ingest;

import org.korhan.quietedit.versioning.DocumentRegistry;
import org.korhan.quietedit.versioning.EncodingVerdict;
import org.korhan.quietedit.versioning.Observation;
import org.korhan.quietedit.versioning.UrlCanonicalizer;
import org.korhan.quietedit.versioning.VersionOutcome;
import org.korhan.quietedit.versioning.VersionStore;
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
 * <p>Versioning is delegated, not decided here: every extracted article is handed
 * to {@link VersionStore}, which owns the rule about when an observation becomes a
 * revision. A run only reports the verdict it gets back, which is what keeps
 * "has this text moved" a single answer with a single implementation.
 *
 * <p>The feed's publication date is normalised here, at the moment the version is
 * written, and against that version's own {@code fetchedAt}. Doing it earlier -- at
 * planning time, against the run's start -- would measure "is this date in the
 * future" against a clock reading that no row records, so a discarded date would be
 * replaced by a timestamp appearing nowhere in the history. Only the feed's
 * publication date is read; its {@code updated} date is deliberately not consulted,
 * because what changed is decided by comparing fetched text, never by a claim.
 *
 * <p>The {@link EncodingVerdict} travels with the article all the way into that
 * store. This is the only stage that knows how the bytes were decoded, so dropping
 * it here would mean reconstructing at classification time a fact that was certain
 * at fetch time.
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
    private final VersionStore versions;
    private final ArticleAttemptLog attemptLog;
    private final Clock clock;
    private final ArticleBudget budget;

    /** False between runs. The single permit that keeps two runs from overlapping. */
    private final AtomicBoolean running = new AtomicBoolean();

    public IngestService(FeedFetchService feedFetchService, FeedParser feedParser,
                         ArticleFetcher articleFetcher, ArticleExtractor articleExtractor,
                         RawHtmlStore rawHtml, UrlCanonicalizer canonicalizer,
                         DocumentRegistry documents, VersionStore versions,
                         ArticleAttemptLog attemptLog, Clock clock,
                         IngestRunProperties properties) {
        this.feedFetchService = feedFetchService;
        this.feedParser = feedParser;
        this.articleFetcher = articleFetcher;
        this.articleExtractor = articleExtractor;
        this.rawHtml = rawHtml;
        this.canonicalizer = canonicalizer;
        this.documents = documents;
        this.versions = versions;
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
                        + "articles {} planned, {} new, {} changed, {} unchanged, {} skipped, {} failed, "
                        + "{} deferred, {} abandoned",
                run.duration(),
                run.feedCount(FeedFetchOutcome.FETCHED), run.feedCount(FeedFetchOutcome.NOT_MODIFIED),
                run.feedCount(FeedFetchOutcome.FAILED),
                run.checked(), run.count(ArticleIngestOutcome.NEW), run.count(ArticleIngestOutcome.CHANGED),
                run.count(ArticleIngestOutcome.UNCHANGED),
                run.count(ArticleIngestOutcome.SKIPPED), run.count(ArticleIngestOutcome.FAILED),
                run.count(ArticleIngestOutcome.DEFERRED), run.count(ArticleIngestOutcome.ABANDONED));
        long mojibake = run.articles().stream()
                .filter(article -> article.encoding() != null && article.encoding().replaced())
                .count();
        if (mojibake > 0) {
            log.warn("{} articles were decoded with replacement characters; their text is mojibake, not prose",
                    mojibake);
        }
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
            // A feed's verdict is logged and dropped on purpose: nothing versions a feed
            // body, so there is no row to record it on. The article verdict is the one
            // that has to survive, and it is resolved separately in read().
            String body = EncodingResolver.resolve(fetch.body(), fetch.contentType(), fetch.url()).text();
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
        EncodingResolver.Decoded decoded;
        try {
            decoded = EncodingResolver.resolve(
                    rawHtml.read(fetch.rawHtmlRef()), fetch.contentType(), fetch.finalUrl());
        } catch (RuntimeException e) {
            return Attempt.decided(candidate, ArticleIngestResult.failed(
                    candidate.link(), fetch.finalUrl(), "raw html unreadable: " + e.getMessage()));
        }
        String html = decoded.text();
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
        return new Attempt(candidate, fetch, canonicalUrl, content, decoded.verdict(), null);
    }

    /**
     * The only stage that writes documents and versions, and it runs single-threaded.
     *
     * <p>Two transactions, not one: identity is established first, then the
     * observation is offered to the version store. Splitting them means a version
     * write that fails leaves the document registered and its {@code lastCheckedAt}
     * updated, so the next run re-checks the article normally instead of the failure
     * also erasing the record that anyone looked. The reverse -- a version without a
     * document -- cannot happen, because the store needs the document id to write one.
     */
    private ArticleIngestResult resolve(Attempt attempt) {
        if (attempt.decided() != null) {
            return attempt.decided();
        }
        Candidate candidate = attempt.candidate();
        ArticleFetchResult fetch = attempt.fetch();
        DocumentRegistry.Registration registration;
        try {
            registration = documents.register(attempt.canonicalUrl(), candidate.feedId(), fetch.fetchedAt());
        } catch (RuntimeException e) {
            log.error("Could not register {}", attempt.canonicalUrl(), e);
            return ArticleIngestResult.failed(candidate.link(), fetch.finalUrl(),
                    "not registered: " + e.getClass().getSimpleName());
        }
        NormalisedDate published = DateNormalizer.normalize(candidate.publishedRaw(), fetch.fetchedAt());
        VersionStore.Stored stored;
        try {
            stored = versions.record(registration.documentId(), new Observation(
                    fetch.fetchedAt(), attempt.content(), statusOf(fetch), candidate.feedTitle(),
                    fetch.rawHtmlRef(), published.instant(), published.exact(), attempt.encoding()));
        } catch (RuntimeException e) {
            log.error("Could not version {}", attempt.canonicalUrl(), e);
            return ArticleIngestResult.failed(candidate.link(), fetch.finalUrl(),
                    "not versioned: " + e.getClass().getSimpleName());
        }
        log.debug("{} {} {} -> v{}", stored.outcome(),
                registration.created() ? "new" : "seen",
                attempt.canonicalUrl(), stored.versionNumber());
        if (stored.outcome() == VersionOutcome.APPENDED && !registration.created()) {
            log.info("{} changed: version {} appended", attempt.canonicalUrl(), stored.versionNumber());
        }
        return ArticleIngestResult.ingested(candidate.link(), fetch.finalUrl(), attempt.canonicalUrl(),
                registration.created(), registration.documentId(), stored, fetch.rawHtmlRef(),
                attempt.content().paragraphs().size(), attempt.encoding());
    }

    /**
     * A fetched article always carries a status; the {@code Integer} on the fetch
     * result is nullable only for the outcomes that never reached this far.
     */
    private static int statusOf(ArticleFetchResult fetch) {
        return fetch.httpStatus() == null ? 0 : fetch.httpStatus();
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
                    FeedEntry entry = parse.entries().get(slot);
                    work.candidates.add(new Candidate(work, slot, fetch.feedId(), link,
                            identity.apply(link), entry.title(), entry.publishedRaw()));
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
     * @param identity     what the budget and the attempt log key this link by, resolved
     *                     before the fetch because the ranking happens before the fetch
     * @param feedTitle    the headline as the feed advertised it, kept beside the one the
     *                     page carries: a feed and a page that disagree about a headline
     *                     is itself a signal, and only the version can record it
     * @param publishedRaw the feed's publication date, still as the publisher wrote it.
     *                     It is normalised in {@link #resolve}, not here, so that the
     *                     retrieval time it is measured against is the very timestamp the
     *                     version is written with rather than a slightly earlier one
     */
    private record Candidate(FeedWork work, int slot, UUID feedId, String link, String identity,
                             String feedTitle, String publishedRaw) {
    }

    /**
     * One article after the network stage. Either {@code decided} is set -- the
     * article needs no document -- or the content, canonical URL and encoding verdict
     * are.
     */
    private record Attempt(Candidate candidate, ArticleFetchResult fetch, String canonicalUrl,
                           ArticleContent content, EncodingVerdict encoding, ArticleIngestResult decided) {

        static Attempt decided(Candidate candidate, ArticleIngestResult result) {
            return new Attempt(candidate, null, null, null, null, result);
        }
    }
}
