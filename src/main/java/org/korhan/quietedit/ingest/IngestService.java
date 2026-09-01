package org.korhan.quietedit.ingest;

import org.korhan.quietedit.versioning.DocumentObservation;
import org.korhan.quietedit.versioning.DocumentRegistry;
import org.korhan.quietedit.versioning.EncodingVerdict;
import org.korhan.quietedit.versioning.Observation;
import org.korhan.quietedit.versioning.UrlCanonicalizer;
import org.korhan.quietedit.versioning.VersionOutcome;
import org.korhan.quietedit.versioning.VersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
import java.util.stream.Stream;

/**
 * One synchronous run: sync the feed catalogue, poll every feed, follow every article
 * link, strip the boilerplate, and hand each article to {@link VersionStore}, which alone
 * decides whether an observation becomes a revision.
 *
 * <p>Every stage turns its expected failures into results rather than exceptions and is
 * additionally wrapped here, so a publisher serving a 500 or a body that is not a feed
 * costs one feed or one article, never the run.
 *
 * <p>Not {@code @Transactional}: a run spends nearly all its wall clock on remote
 * servers, so writes happen in short transactions per feed row and per document.
 *
 * <p>Two concurrent runs would fetch the same articles and race on the unique
 * constraint of {@code document.canonical_url}, so a second run is refused rather than
 * queued; the guard is this process's, and a second instance would need a database lock.
 *
 * <p>Fetches fan out across all feeds' links at once rather than feed by feed, because
 * {@link HostRateLimiter} already serialises per host and going in feed order would
 * idle every other publisher; documents are then registered serially, so two threads
 * cannot race to create one.
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
    private final UpdatedClaimLog updatedClaims;
    private final Clock clock;
    private final ArticleBudget budget;
    private final RecheckPolicy recheck;
    private final int maxRecheckCandidates;

    /** The single permit that keeps two runs from overlapping. */
    private final AtomicBoolean running = new AtomicBoolean();

    public IngestService(FeedFetchService feedFetchService, FeedParser feedParser,
                         ArticleFetcher articleFetcher, ArticleExtractor articleExtractor,
                         RawHtmlStore rawHtml, UrlCanonicalizer canonicalizer,
                         DocumentRegistry documents, VersionStore versions,
                         ArticleAttemptLog attemptLog, UpdatedClaimLog updatedClaims, Clock clock,
                         IngestRunProperties properties, RecheckProperties recheckProperties) {
        this.feedFetchService = feedFetchService;
        this.feedParser = feedParser;
        this.articleFetcher = articleFetcher;
        this.articleExtractor = articleExtractor;
        this.rawHtml = rawHtml;
        this.canonicalizer = canonicalizer;
        this.documents = documents;
        this.versions = versions;
        this.attemptLog = attemptLog;
        this.updatedClaims = updatedClaims;
        this.clock = clock;
        this.budget = new ArticleBudget(properties.maxArticles(), properties.maxArticleFailures());
        this.recheck = recheckProperties.toPolicy();
        this.maxRecheckCandidates = recheckProperties.maxCandidatesPerRun();
    }

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
        List<Candidate> feedCandidates = work.stream().flatMap(feed -> feed.candidates.stream()).toList();

        Map<String, DocumentObservation> known = new HashMap<>(documents.observationsOf(
                feedCandidates.stream().map(Candidate::identity).collect(Collectors.toSet())));
        RecheckWork rechecks = offerStoredDocuments(startedAt, known);

        List<Candidate> candidates = Stream.concat(feedCandidates.stream(), rechecks.candidates.stream()).toList();
        Planned planned = due(startedAt, candidates, known);

        Map<UUID, UpdatedClaimTally> evidence = new LinkedHashMap<>();
        for (Attempt attempt : fetchAll(admit(planned.candidates()))) {
            Candidate candidate = attempt.candidate();
            ArticleIngestResult result = resolve(attempt);
            candidate.sink().accept(candidate.slot(), result);
            recordAttempt(candidate, result);
            if (planned.underAStandingClaim().contains(candidate.identity())) {
                score(evidence, candidate, result);
            }
        }
        recordUpdatedClaims(evidence);

        IngestRun run = new IngestRun(startedAt, clock.instant(), feedRun.catalog(),
                work.stream().map(FeedWork::toResult).toList(), rechecks.results());
        log.info("Ingest run finished in {}: feeds {} fetched / {} unchanged / {} failed; "
                        + "articles {} planned ({} re-check candidates the feeds no longer carry), "
                        + "{} new, {} changed, {} unchanged, {} skipped, {} failed, "
                        + "{} not due, {} deferred, {} abandoned",
                run.duration(),
                run.feedCount(FeedFetchOutcome.FETCHED), run.feedCount(FeedFetchOutcome.NOT_MODIFIED),
                run.feedCount(FeedFetchOutcome.FAILED),
                run.checked(), rechecks.candidates.size(),
                run.count(ArticleIngestOutcome.NEW), run.count(ArticleIngestOutcome.CHANGED),
                run.count(ArticleIngestOutcome.UNCHANGED),
                run.count(ArticleIngestOutcome.SKIPPED), run.count(ArticleIngestOutcome.FAILED),
                run.count(ArticleIngestOutcome.NOT_DUE),
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
     * A publisher's feed carries roughly a day of articles while the observation window
     * is measured in days, so without reading the store back the re-check curve would
     * stop being applied where a feed drops an entry.
     *
     * <p>The query uses the widest bounds any candidate could pass and leaves the
     * per-document decision to the policy: encoding the curve in SQL would be a second
     * implementation of it.
     */
    private RecheckWork offerStoredDocuments(Instant now, Map<String, DocumentObservation> known) {
        RecheckWork work = new RecheckWork();
        List<DocumentObservation> stored = documents.observationsPossiblyDue(
                now.minus(recheck.minInterval()), now.minus(recheck.observationWindow()),
                now.minus(recheck.widestWindow()), maxRecheckCandidates);
        if (stored.size() == maxRecheckCandidates) {
            log.warn("Re-check backlog reached the per-run ceiling of {}; the rest waits for the next run",
                    maxRecheckCandidates);
        }
        for (DocumentObservation observation : stored) {
            // Keyed by the page, not the document: syndication gives one document two URLs.
            if (known.putIfAbsent(observation.fetchUrl(), observation) == null) {
                work.offer(observation);
            }
        }
        return work;
    }

    /**
     * Runs before {@link #admit}: a budget applied first would spend its ceiling ranking
     * candidates that were not due and report them as deferred, claiming the next run
     * will reach them when nothing needs to.
     */
    private Planned due(Instant now, List<Candidate> candidates,
                        Map<String, DocumentObservation> known) {
        if (candidates.isEmpty()) {
            return new Planned(candidates, Set.of());
        }
        Map<UUID, Integer> claims = updatedClaims.unconfirmedClaimsOf(
                candidates.stream().map(Candidate::feedId).collect(Collectors.toSet()));
        List<RecheckState> states = candidates.stream()
                .map(candidate -> stateOf(now, candidate, known, claims))
                .toList();
        RecheckPolicy.Plan plan = recheck.plan(now, states);

        List<Candidate> due = new ArrayList<>(plan.due().size());
        Set<String> underAStandingClaim = new HashSet<>();
        int ignoredClaims = 0;
        for (int position = 0; position < candidates.size(); position++) {
            Candidate candidate = candidates.get(position);
            RecheckState state = states.get(position);
            if (state.seen() && RecheckPolicy.claimsAnEditSinceTheLastCheck(state)) {
                underAStandingClaim.add(candidate.identity());
                if (!recheck.believesUpdatedClaims(state)) {
                    ignoredClaims++;
                }
            }
            RecheckDecision decision = plan.at(position);
            if (decision == RecheckDecision.DUE) {
                due.add(candidate);
            } else {
                candidate.sink().accept(candidate.slot(),
                        ArticleIngestResult.notDue(candidate.link(), decision));
            }
        }
        if (plan.count(RecheckDecision.THROTTLED) > 0) {
            log.warn("{} candidates were held back by the per-host ceiling of {} requests an hour",
                    plan.count(RecheckDecision.THROTTLED), recheck.maxRequestsPerHostPerHour());
        }
        if (ignoredClaims > 0) {
            log.info("{} feed 'updated' claims were read but not acted on: their feeds have run up "
                            + "{} unconfirmed claims and are back on the re-check curve",
                    ignoredClaims, recheck.maxUnconfirmedUpdatedClaims());
        }
        log.debug("Re-check policy: {} of {} candidates due, {} waiting, {} retired, {} throttled",
                due.size(), candidates.size(), plan.count(RecheckDecision.WAITING),
                plan.count(RecheckDecision.RETIRED), plan.count(RecheckDecision.THROTTLED));
        return new Planned(due, underAStandingClaim);
    }

    /**
     * Only the outcomes that settle whether the text moved are counted: a skipped,
     * failed or newly registered article tests nothing about the claim that stood over
     * it, and counting it would make a publisher's credibility depend on their paywall.
     */
    private static void score(Map<UUID, UpdatedClaimTally> evidence, Candidate candidate,
                              ArticleIngestResult result) {
        switch (result.outcome()) {
            case CHANGED -> evidence.merge(candidate.feedId(),
                    UpdatedClaimTally.NONE.plus(true), UpdatedClaimTally::plus);
            case UNCHANGED -> evidence.merge(candidate.feedId(),
                    UpdatedClaimTally.NONE.plus(false), UpdatedClaimTally::plus);
            default -> { }
        }
    }

    /**
     * Contained per feed: the rule is a running count precisely so that a lost
     * increment is survivable.
     */
    private void recordUpdatedClaims(Map<UUID, UpdatedClaimTally> evidence) {
        evidence.forEach((feedId, tally) -> {
            try {
                int unconfirmed = updatedClaims.record(feedId, tally);
                log.debug("Feed {}: {} of {} standing 'updated' claims confirmed, {} unconfirmed in a row",
                        feedId, tally.confirmed(), tally.fetches(), unconfirmed);
            } catch (RuntimeException e) {
                log.error("Could not record the 'updated' claim evidence for feed {}", feedId, e);
            }
        });
    }

    /**
     * The feed's {@code updated} date is normalised against the run's start, because the
     * question is asked before anything is fetched and there is no later timestamp yet.
     * It is handed over whole, exactness flag included: whether an inexact claim is
     * worth acting on is the policy's decision.
     */
    private RecheckState stateOf(Instant now, Candidate candidate,
                                 Map<String, DocumentObservation> known, Map<UUID, Integer> claims) {
        String host = hostOf(candidate.identity());
        DocumentObservation observation = known.get(candidate.identity());
        if (observation == null) {
            return RecheckState.unseen(host);
        }
        return new RecheckState(host, observation.firstSeenAt(), observation.lastCheckedAt(),
                observation.lastChangedAt(), observation.versionCount(),
                DateNormalizer.normalize(candidate.updatedRaw(), now),
                claims.getOrDefault(candidate.feedId(), 0));
    }

    /**
     * A URL with no readable host becomes its own rate-limit bucket rather than being
     * dropped or lumped in with the others, which costs nothing: {@link ArticleFetcher}
     * refuses such a URL anyway, so the one request the bucket permits is the one that
     * reports it broken.
     */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host != null && !host.isBlank()) {
                return host.toLowerCase(Locale.ROOT);
            }
        } catch (RuntimeException e) {
            log.debug("No host in {}; counted as a bucket of its own", url);
        }
        return url;
    }

    /**
     * The attempt log is consulted even when the run fits inside its ceiling: ranking is
     * pointless then, but abandonment is not, or a small catalogue would re-fetch a
     * permanently broken link on every poll.
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
                candidate.sink().accept(candidate.slot(), ArticleIngestResult.abandoned(
                        candidate.link(), history.get(candidate.identity()).failureCount()));
            } else {
                candidate.sink().accept(candidate.slot(), ArticleIngestResult.deferred(candidate.link()));
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
     * A document id is the success test rather than the outcome enum: it is the one
     * thing that means "this link resolved to an article", however the run labelled it.
     * A log that cannot be written is a lost strike, not a lost article.
     */
    private void recordAttempt(Candidate candidate, ArticleIngestResult result) {
        try {
            attemptLog.record(candidate.identity(), clock.instant(), result.documentId() != null);
        } catch (RuntimeException e) {
            log.error("Could not record the attempt on {}", candidate.identity(), e);
        }
    }

    /**
     * The identity a link would have without redirects and without a
     * {@code rel=canonical} of its own -- the only one available before the fetch, and
     * good enough to rank by, since a link whose real identity differs is at worst
     * ranked as unseen.
     *
     * <p>An uncanonicalisable link falls back to the raw link so that it still
     * accumulates strikes; left unidentified it would rank as never-tried forever and
     * starve the catalogue behind it.
     */
    private String provisionalIdentity(String link) {
        try {
            return canonicalizer.canonicalize(link);
        } catch (RuntimeException e) {
            return link;
        }
    }

    /** A 304 or a failed fetch carries no body; that is not an error here, just no entries. */
    private FeedWork plan(FeedFetchResult fetch, Set<String> seenLinks) {
        if (fetch.outcome() != FeedFetchOutcome.FETCHED) {
            return FeedWork.withoutEntries(fetch, fetch.failureReason());
        }
        FeedParseResult parse;
        try {
            // The feed's verdict is dropped: nothing versions a feed body, so there is no row.
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
     * The HTML is read back from the store rather than carried through the fetch result:
     * a run has hundreds of articles in flight and only one belongs in memory.
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
     * Two transactions, not one: a failed version write still leaves the document
     * registered and its {@code lastCheckedAt} updated, so the next run re-checks
     * normally instead of the failure also erasing the record that anyone looked. The
     * reverse cannot happen, because the store needs the document id.
     */
    private ArticleIngestResult resolve(Attempt attempt) {
        if (attempt.decided() != null) {
            return attempt.decided();
        }
        Candidate candidate = attempt.candidate();
        ArticleFetchResult fetch = attempt.fetch();
        DocumentRegistry.Registration registration;
        try {
            registration = documents.register(attempt.canonicalUrl(), candidate.identity(),
                    candidate.feedId(), fetch.fetchedAt());
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

    /** Nullable only for the fetch outcomes that never reach this far. */
    private static int statusOf(ArticleFetchResult fetch) {
        return fetch.httpStatus() == null ? 0 : fetch.httpStatus();
    }

    /**
     * Both candidate sources need their results back in their own order, so a candidate
     * carries its sink rather than the run branching on where it came from. Written from
     * the calling thread only.
     */
    private interface ResultSink {
        void accept(int slot, ArticleIngestResult result);
    }

    /** Slot table: entries keep their feed position however the concurrent fetches finish. */
    private static final class FeedWork implements ResultSink {

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

        @Override
        public void accept(int slot, ArticleIngestResult result) {
            articles[slot] = result;
        }

        /**
         * {@code seenLinks} is the whole run's, not this feed's: a link two feeds
         * advertise would otherwise be fetched twice and the second fetch would race the
         * first for the document's creation. Feed order decides which feed wins it.
         */
        static FeedWork parsed(FeedFetchResult fetch, FeedParseResult parse, Set<String> seenLinks,
                               UnaryOperator<String> identity) {
            FeedWork work = new FeedWork(fetch, parse, null, parse.entries().size());
            for (int slot = 0; slot < parse.entries().size(); slot++) {
                String link = parse.entries().get(slot).link().trim();
                if (seenLinks.add(link)) {
                    FeedEntry entry = parse.entries().get(slot);
                    work.candidates.add(new Candidate(work, slot, fetch.feedId(), link,
                            identity.apply(link), entry.title(), entry.publishedRaw(), entry.updatedRaw()));
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
     * The due documents no feed advertises any more. They carry no title and no dates:
     * a feed that dropped an entry has no current claim about it, and reusing the entry
     * it used to carry would put a stale claim on a fresh version row.
     */
    private static final class RecheckWork implements ResultSink {

        private final List<Candidate> candidates = new ArrayList<>();
        private final List<ArticleIngestResult> articles = new ArrayList<>();

        /**
         * The observed origin, not the canonical URL: a syndicated copy is filed under
         * the original publisher's canonical URL, so re-checking that URL fetches a page
         * whose text this document never carried and reports an edit nobody made.
         */
        void offer(DocumentObservation observation) {
            articles.add(null);
            candidates.add(new Candidate(this, articles.size() - 1, observation.feedId(),
                    observation.fetchUrl(), observation.fetchUrl(), null, null, null));
        }

        @Override
        public void accept(int slot, ArticleIngestResult result) {
            articles.set(slot, result);
        }

        List<ArticleIngestResult> results() {
            return articles.stream().filter(Objects::nonNull).toList();
        }
    }

    /**
     * @param publishedRaw verbatim; normalised in {@link #resolve} so that it is measured
     *                     against the very timestamp its version is written with
     * @param updatedRaw   verbatim; normalised in {@link #stateOf} against the run's
     *                     start, and reaches no version row
     */
    private record Candidate(ResultSink sink, int slot, UUID feedId, String link, String identity,
                             String feedTitle, String publishedRaw, String updatedRaw) {
    }

    /**
     * @param underAStandingClaim the identities whose feed claimed an edit since the last
     *                            look, whether or not that is why they are due. Carried
     *                            because {@link UpdatedClaimTally} needs the claim, known
     *                            only before the fetch, and the verdict, known only after
     */
    private record Planned(List<Candidate> candidates, Set<String> underAStandingClaim) {
    }

    /** Either {@code decided} is set, or the content, canonical URL and verdict are. */
    private record Attempt(Candidate candidate, ArticleFetchResult fetch, String canonicalUrl,
                           ArticleContent content, EncodingVerdict encoding, ArticleIngestResult decided) {

        static Attempt decided(Candidate candidate, ArticleIngestResult result) {
            return new Attempt(candidate, null, null, null, null, result);
        }
    }
}
