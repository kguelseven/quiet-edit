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
 * replaced by a timestamp appearing nowhere in the history.
 *
 * <p>The feed's {@code updated} date reaches nothing that a version records. It is
 * read at planning time and handed to {@link RecheckPolicy} alone, where it means
 * "worth another look"; what actually changed is still decided by comparing fetched
 * text against the newest stored revision, never by a publisher's claim. The two
 * dates are therefore normalised at different moments against different reference
 * times, which is correct: one is evidence about an article, the other is a hint
 * about when to fetch it.
 *
 * <p>A run is also where those hints are scored. Whenever a claim was standing over
 * an article at planning time, whatever the article was fetched <em>for</em>, the
 * verdict the version store reaches about its text is evidence about the feed that
 * made the claim, and it is handed to {@link UpdatedClaimLog}. That is the only place
 * the two halves meet -- the claim is known before the fetch, the verdict only after
 * it -- so it has to happen here rather than in the policy, which may not remember
 * anything.
 *
 * <p>A run has two sources of candidates. Every feed's links are one; the documents
 * {@link RecheckPolicy} still wants to watch are the other, and they are needed
 * because a feed drops an article after a day or so while the edits worth catching go
 * on for longer. Both sources meet the same two gates in the same order: the policy
 * decides <em>whether it is time</em> for each candidate, and {@link ArticleBudget}
 * then decides <em>how many</em> fit into this run and in which order. A stored
 * document a feed still advertises is offered once, by the feed, so that the entry's
 * dates and title are not thrown away.
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
    private final UpdatedClaimLog updatedClaims;
    private final Clock clock;
    private final ArticleBudget budget;
    private final RecheckPolicy recheck;
    private final int maxRecheckCandidates;

    /** False between runs. The single permit that keeps two runs from overlapping. */
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
     * Offers the documents the re-check policy could still want, minus the ones a feed
     * is offering anyway, and records their state in {@code known} so that the policy
     * sees one entry per candidate.
     *
     * <p>This is the half of the policy a feed cannot provide. A publisher's feed
     * carries roughly a day of articles, and the observation window is measured in
     * days, so without reading the store back the curve would simply stop being
     * applied at the point where a feed drops an entry -- which is well before the
     * point where this system stops caring about it.
     *
     * <p>The store is queried with the widest bounds any candidate could pass and the
     * per-document decision is left to the policy: a query that tried to encode the
     * curve would be a second implementation of it, in SQL, against the same numbers.
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
            // A document a feed still advertises is already a candidate, and that
            // candidate is the better one: it carries the entry's dates and title.
            if (known.putIfAbsent(observation.canonicalUrl(), observation) == null) {
                work.offer(observation);
            }
        }
        return work;
    }

    /**
     * Applies the re-check policy, deciding every candidate whose turn it is not.
     *
     * <p>Runs before {@link #admit}, and the order matters: the policy answers
     * "should anyone fetch this now", the budget answers "can this run afford to". A
     * budget applied first would spend its ceiling ranking candidates that were not
     * due, and would report them as deferred -- which claims the next run will reach
     * them, when what is true is that nothing needs to.
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
     * Adds what one fetch found to its feed's tally, but only for the outcomes that
     * settle whether the text moved. A skipped, failed or newly registered article
     * tests nothing about the claim that stood over it, and counting it either way
     * would make a publisher's credibility depend on their paywall.
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
     * Writes back what this run learned about each feed's {@code updated} dates.
     *
     * <p>Contained per feed like every other write a run makes: a strike that cannot
     * be recorded costs one run's worth of evidence about one publisher, and the rule
     * is a running count precisely so that a lost increment is survivable.
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
     * One candidate's state, as the policy is allowed to see it.
     *
     * <p>The feed's {@code updated} date is normalised here and handed over whole,
     * exactness flag included. Whether an inexact claim is worth acting on is the
     * policy's decision, and it is spelled out there; normalising against the run's
     * start is this method's, because the question is asked before anything is fetched
     * and there is no later timestamp to measure against yet.
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
     * The bucket a candidate's requests are counted in.
     *
     * <p>A URL with no readable host becomes its own bucket rather than being dropped
     * or lumped in with the others. It gets a whole host's ceiling to itself, which
     * costs nothing: {@link ArticleFetcher} refuses a URL without a host anyway, so the
     * one request the bucket permits is the one that reports it broken.
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
     * Where a candidate's result goes. Two things produce candidates -- a feed's
     * entries and the store's due documents -- and both need their results back in
     * their own order, so a candidate carries its sink rather than the run branching
     * on where it came from.
     *
     * <p>Written from the calling thread only. The fan-out stage touches no result.
     */
    private interface ResultSink {
        void accept(int slot, ArticleIngestResult result);
    }

    /**
     * One feed's slot table. Every parsed entry keeps its position, so a run's report
     * lists articles in feed order however the concurrent fetches happened to finish.
     */
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
     * The documents the re-check policy wants looked at that no feed advertises any
     * more. Its own slot table for the same reason a feed has one: a result belongs at
     * the position its candidate had, whatever order the fetches finished in.
     *
     * <p>These candidates carry no title and no dates, because no feed said anything
     * about them this run. That is not a loss of information: a feed that has dropped
     * an entry has no current claim about it, and inventing one from the entry it used
     * to carry would put a stale claim on a fresh version row.
     */
    private static final class RecheckWork implements ResultSink {

        private final List<Candidate> candidates = new ArrayList<>();
        private final List<ArticleIngestResult> articles = new ArrayList<>();

        /**
         * The document's canonical URL serves as link and identity at once. It is what
         * the store is keyed by and what the attempt log will key by, and unlike a feed
         * link it needs no provisional resolution -- it is already the real identity.
         */
        void offer(DocumentObservation observation) {
            articles.add(null);
            candidates.add(new Candidate(this, articles.size() - 1, observation.feedId(),
                    observation.canonicalUrl(), observation.canonicalUrl(), null, null, null));
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
     * @param sink         where this candidate's result belongs, and at which slot
     * @param identity     what the budget, the attempt log and the re-check policy key
     *                     this link by, resolved before the fetch because all three
     *                     decide before the fetch
     * @param feedTitle    the headline as the feed advertised it, kept beside the one the
     *                     page carries: a feed and a page that disagree about a headline
     *                     is itself a signal, and only the version can record it
     * @param publishedRaw the feed's publication date, still as the publisher wrote it.
     *                     It is normalised in {@link #resolve}, not here, so that the
     *                     retrieval time it is measured against is the very timestamp the
     *                     version is written with rather than a slightly earlier one
     * @param updatedRaw   the feed's {@code updated} date, verbatim. Normalised in
     *                     {@link #stateOf} against the run's start, because it is read
     *                     to decide whether to fetch at all and so has to be answered
     *                     before anything is fetched. It reaches no version row.
     */
    private record Candidate(ResultSink sink, int slot, UUID feedId, String link, String identity,
                             String feedTitle, String publishedRaw, String updatedRaw) {
    }

    /**
     * What the re-check policy left for the run to do, plus the one thing about the
     * planning step the fetch stage still needs afterwards.
     *
     * @param candidates          the candidates the policy said yes to, in their own order
     * @param underAStandingClaim the identities whose feed claimed an edit since the
     *                            last look, whether or not that claim is why they are
     *                            in {@code candidates}. Kept because the claim is only
     *                            knowable before the fetch and its verdict only after,
     *                            and {@link UpdatedClaimTally} needs both
     */
    private record Planned(List<Candidate> candidates, Set<String> underAStandingClaim) {
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
