package org.korhan.quietedit.ingest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Decides when a known article is fetched again, as a pure function: the candidates'
 * state and one clock reading go in, one decision per candidate comes out. No HTTP,
 * no {@code Clock}, no repository -- which is what makes every property below an
 * assertion over records rather than a scenario against a database.
 *
 * <h2>Why not a fixed interval</h2>
 * A fixed interval is wrong in both directions at once. The edits this system exists
 * to catch cluster in the hours right after publication -- a headline sharpened, a
 * number corrected, a paragraph pulled -- so an interval long enough to be affordable
 * across a whole catalogue misses precisely those, while an interval short enough to
 * catch them is still being paid weeks later on articles nobody will touch again.
 *
 * <h2>The curve: a constant fraction of the article's age</h2>
 * The interval is {@code age * ageFactor}, clamped to {@code [minInterval,
 * maxInterval]}. With the defaults -- a quarter of the age, floored at ten minutes,
 * capped at twelve hours -- a candidate is re-checked roughly at:
 *
 * <pre>
 *   age 10 min -> 10 min      age 12 h -> 3 h
 *   age  1 h   -> 15 min      age  1 d -> 6 h
 *   age  4 h   ->  1 h        age  2 d -> 12 h (the cap)
 * </pre>
 *
 * <p>Self-similar on purpose, and that is the whole justification: the rate at which
 * a news article is still being edited falls off roughly like {@code 1/age}, so
 * checking after a constant <em>fraction</em> of the age keeps the expected number of
 * edits missed per check roughly constant over the article's whole life. A curve
 * built from tiers ("every 10 minutes for an hour, then every hour for a day")
 * encodes the same intent with five arbitrary numbers instead of one, and each of
 * them is a boundary an article can sit just the wrong side of.
 *
 * <p>The two clamps are the honest parts. {@code minInterval} is politeness: without
 * a floor the interval goes to zero as the age does, and a freshly discovered article
 * would be re-fetched as fast as the run loop turns. {@code maxInterval} bounds how
 * stale the newest observation of a still-watched document may be, so that an article
 * edited on its last day under observation is still caught within half a day.
 *
 * <h2>Age is measured from what we saw, not from what was claimed</h2>
 * The reference is {@link RecheckState#lastEventAt()}: the document's most recent
 * observed edit, or its discovery while it has never moved. Two decisions in that.
 *
 * <p>Discovery rather than the publisher's date, because the publication date is a
 * claim and {@code firstSeenAt} is a fact. Feeds are polled every few minutes, so for
 * an article discovered through its feed the two agree to within one poll interval,
 * and where they disagree it is usually because the publisher back-dated, re-dated or
 * templated the field -- exactly the cases where trusting the claim would put the
 * article in the wrong part of the curve. Known weakness: an <em>archive</em> article
 * that enters a feed years after publication is discovered today and therefore gets a
 * fresh article's attention for a few days. That costs a handful of requests and is
 * tracked separately rather than fixed by trusting the date.
 *
 * <p>The most recent edit rather than only the discovery, because edits cluster the
 * same way publication does: the minutes after an observed change are as interesting
 * as the minutes after publication, and the article is demonstrably being worked on.
 * An observed edit therefore restarts the curve at its steep end.
 *
 * <h2>An {@code updated} claim in the feed is a reason to look</h2>
 * When a feed says an entry was updated after the last time this system looked, the
 * candidate is due at once -- interval and observation window both overridden. Note
 * what this does and does not do: the claim decides <em>when to look</em>, never
 * <em>whether something changed</em>. Whether the text moved is still settled by
 * comparing fetched text against the newest stored revision, so a publisher who
 * touches {@code updated} on every render costs requests and produces no revisions.
 *
 * <p>Only a date that normalised <em>exactly</em> is acted on. An {@code updated}
 * field with no timezone is off by up to half a day and a date without a time by a
 * whole one, which is far too coarse to answer "is this newer than our last check";
 * a date so far in the future that {@link DateNormalizer} replaced it with the
 * retrieval time would answer "yes" forever. Those all fall back to the curve, which
 * needed no claim in the first place.
 *
 * <h2>A feed whose claims never come true stops being believed</h2>
 * A CMS that stamps {@code updated} with the render time would otherwise make every
 * one of its entries permanently due, and the per-host ceiling is a poor answer to
 * that: it bounds the cost by starving the same host's real re-checks, so the
 * publisher whose dates are noise takes the requests away from the articles that
 * actually need watching.
 *
 * <p>So the claim has to earn its override. A feed carries a strike count -- fetches
 * made while one of its claims stood that found the text exactly where it was,
 * cleared by the first such fetch that appends a revision (see
 * {@link UpdatedClaimTally}). Once that count reaches
 * {@code maxUnconfirmedUpdatedClaims}, the feed's {@code updated} dates stop
 * overriding anything and its articles are re-checked on the curve like everything
 * else. Nothing else about them changes: they are still fetched, still versioned,
 * still watched for their whole observation window. What is withdrawn is only the
 * publisher's power to say "now".
 *
 * <p>Per feed, not per document, because a templated {@code updated} field is a
 * property of the CMS and shows itself across the whole feed at once. One document
 * could never carry enough evidence to tell a template apart from an article that
 * genuinely is being worked on, and waiting for per-document evidence would mean
 * paying the full cost on every entry before recognising any of them.
 *
 * <p>Twenty is the default, and the number that matters about it is how fast a
 * misbehaving feed is caught. A feed carries a day or so of entries -- a few dozen --
 * and the run after the one that discovered them is the first where every entry has
 * a claim standing over a previous check. That run spends its requests, finds nothing
 * moved, and puts the feed well past twenty; from the next run on, the CMS costs
 * nothing beyond the curve. Twenty is also comfortably more than one busy article can
 * produce on its own, and the whole count is wiped by a single confirmed claim, so a
 * publisher who really does edit is never near it.
 *
 * <p>Recovering is possible on purpose, and it is why the strike count is fed by
 * <em>every</em> fetch under a standing claim rather than only by the ones the claim
 * caused. A distrusted feed's articles keep being fetched on the curve; each of those
 * fetches still tests the claim standing over it, and the first one that finds a real
 * edit clears the record and hands the publisher its override back.
 *
 * <p>Known weakness: the evidence is only ever collected from articles still under
 * observation. A feed that is distrusted and then reforms is believed again as soon
 * as one of its live articles is really edited, but a claim about an article this
 * system has already retired cannot bring that article back any more, and so cannot
 * be the thing that rehabilitates the feed.
 *
 * <h2>An article that keeps changing stays under observation longer</h2>
 * A candidate is retired -- never re-checked again -- once nothing has happened to it
 * for its observation window. The window is {@code observationWindow * (1 + changes)},
 * capped at {@code maxWindowFactor}, and it too is measured from
 * {@link RecheckState#lastEventAt()}. Both halves of that pull in the same direction:
 * every observed edit pushes the deadline out by a full window, and each one also
 * widens the window itself. With the defaults, a stable article is watched for seven
 * days after discovery; one edited once is watched for fourteen days after that edit;
 * one edited three times or more for twenty-eight days after its last. A stable
 * article and a volatile one can therefore never be retired on the same schedule.
 *
 * <p>{@code maxWindowFactor} is the cap that keeps this from being unbounded: a page
 * whose extracted text genuinely churns -- a live blog, a ticker that survived
 * boilerplate removal -- would otherwise renew its own window forever. Four is chosen
 * because it is where the mechanism stops adding information: an article observed to
 * change four times is not a candidate for "silently edited once", it is a page that
 * changes, and watching it for a fifth window would say nothing the first four did
 * not.
 *
 * <p>Retirement is a decision, not a deletion: nothing is removed, and an
 * {@code updated} claim brings a retired candidate back for one look.
 *
 * <h2>The per-host hourly ceiling</h2>
 * At most {@code maxRequestsPerHostPerHour} candidates on one host are admitted per
 * hour, counting both re-checks and first fetches -- it is a request ceiling, and a
 * publisher cannot tell the difference. The hour is a trailing window: a candidate
 * whose last check falls inside it has already spent one of that host's requests, and
 * every candidate this call admits spends one more.
 *
 * <p>This is a second, blunter limit next to {@link HostRateLimiter}, and it exists
 * because they bound different things. The rate limiter spaces requests, so it bounds
 * <em>burstiness</em> and would happily send 3600 requests an hour at a one-second
 * gap. This bounds the <em>total</em>, which is the number a publisher's operations
 * team would recognise as load, and it is the only limit that can say no to work the
 * curve keeps generating.
 *
 * <p>When a host's hour is full, the candidates that keep their slots are the ones
 * that would lose most by waiting: never-seen candidates first, then the longest
 * overdue. An unseen article is the one observation that cannot be taken later -- miss
 * the original wording and it is gone -- while a re-check that slips by one run loses
 * one sample of a text that is still there. Ties go to candidate position, which is
 * feed order and is stable, so two runs over unchanged state cannot disagree about
 * what they throttled.
 *
 * <p>Scope, stated plainly: the ceiling counts the candidates <em>offered to this
 * call</em>. Feed polls are not among them and are not counted, because their number
 * is fixed by the catalogue and the poll interval rather than by anything this policy
 * decides. Spend already made is reconstructed from the candidates' own
 * {@code lastCheckedAt}, so a request that failed before it could update a document
 * is not counted against the hour. Both are undercounts by at most a bounded, known
 * amount; a ledger that counted every outbound request exactly would have to be
 * stateful, and this function may not be.
 *
 * @param minInterval                the floor under the curve; politeness, not a
 *                                   measurement
 * @param maxInterval                the ceiling over it, bounding how stale a watched
 *                                   document's newest observation may be
 * @param ageFactor                  the fraction of an article's age it is re-checked
 *                                   after
 * @param observationWindow          how long a stable article is watched after the
 *                                   last thing that happened to it
 * @param maxWindowFactor            how many windows an article's observed edits can
 *                                   earn it
 * @param maxRequestsPerHostPerHour  the trailing-hour ceiling per host
 * @param maxUnconfirmedUpdatedClaims  how many of a feed's {@code updated} claims may
 *                                   lead to nothing in a row before the feed's claims
 *                                   stop overriding the curve
 */
public record RecheckPolicy(
        Duration minInterval,
        Duration maxInterval,
        double ageFactor,
        Duration observationWindow,
        int maxWindowFactor,
        int maxRequestsPerHostPerHour,
        int maxUnconfirmedUpdatedClaims) {

    /** The ceiling's window. Fixed at an hour because the criterion it serves is hourly. */
    private static final Duration CEILING_WINDOW = Duration.ofHours(1);

    public RecheckPolicy {
        requirePositive(minInterval, "minInterval");
        requirePositive(maxInterval, "maxInterval");
        requirePositive(observationWindow, "observationWindow");
        if (maxInterval.compareTo(minInterval) < 0) {
            throw new IllegalArgumentException("maxInterval must be >= minInterval");
        }
        if (!(ageFactor > 0) || !Double.isFinite(ageFactor)) {
            throw new IllegalArgumentException("ageFactor must be a positive finite number");
        }
        if (maxWindowFactor < 1) {
            throw new IllegalArgumentException("maxWindowFactor must be >= 1");
        }
        if (maxRequestsPerHostPerHour < 1) {
            throw new IllegalArgumentException("maxRequestsPerHostPerHour must be >= 1");
        }
        if (maxUnconfirmedUpdatedClaims < 1) {
            throw new IllegalArgumentException("maxUnconfirmedUpdatedClaims must be >= 1");
        }
    }

    /**
     * Decides every candidate offered, in one pass, so that the per-host ceiling can
     * be applied to the whole set rather than one candidate at a time.
     *
     * @param now    the run's clock reading, passed in rather than read, which is what
     *               makes this function pure
     * @param states one per candidate, in the caller's own order; positions in the
     *               result line up with positions here
     */
    public Plan plan(Instant now, List<RecheckState> states) {
        List<RecheckDecision> decisions = new ArrayList<>(states.size());
        Map<String, Integer> spent = new HashMap<>();
        for (RecheckState state : states) {
            decisions.add(decide(now, state));
            if (state.lastCheckedAt() != null
                    && state.lastCheckedAt().isAfter(now.minus(CEILING_WINDOW))) {
                spent.merge(state.host(), 1, Integer::sum);
            }
        }

        for (int position : byUrgency(states, decisions)) {
            String host = states.get(position).host();
            int used = spent.getOrDefault(host, 0);
            if (used < maxRequestsPerHostPerHour) {
                spent.put(host, used + 1);
            } else {
                decisions.set(position, RecheckDecision.THROTTLED);
            }
        }
        return new Plan(decisions);
    }

    /**
     * The order the hour's remaining slots are handed out in: never-seen candidates
     * first -- a first observation cannot be taken later -- then the longest overdue,
     * then candidate position, which is stable.
     */
    private static List<Integer> byUrgency(List<RecheckState> states, List<RecheckDecision> decisions) {
        List<Integer> due = new ArrayList<>();
        for (int position = 0; position < decisions.size(); position++) {
            if (decisions.get(position) == RecheckDecision.DUE) {
                due.add(position);
            }
        }
        due.sort(Comparator
                .comparing((Integer position) -> states.get(position).lastCheckedAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Comparator.naturalOrder()));
        return due;
    }

    /**
     * Whether the feed's own {@code updated} field says something happened after this
     * system last looked. Only an exactly normalised date counts, for the reason given
     * in the class comment: an inexact one cannot answer a question asked in minutes.
     *
     * <p>Public because it is also the rule for what counts as evidence about a feed:
     * a fetch tests a claim exactly when a claim was standing over it, and the run
     * that collects that evidence must ask the same question this class answers rather
     * than a lookalike of its own.
     *
     * @throws NullPointerException if the candidate has never been checked -- an
     *                              unseen candidate has no last look for a claim to be
     *                              newer than, and is fetched regardless
     */
    public static boolean claimsAnEditSinceTheLastCheck(RecheckState state) {
        NormalisedDate updated = state.feedUpdated();
        return updated.exact() && updated.instant().isAfter(state.lastCheckedAt());
    }

    /**
     * Whether this candidate's feed has any credit left for its {@code updated} dates.
     * False means the claims are still read and still counted -- they are simply no
     * longer a reason to fetch at once. The justification is in the class comment.
     */
    public boolean believesUpdatedClaims(RecheckState state) {
        return state.unconfirmedUpdatedClaims() < maxUnconfirmedUpdatedClaims;
    }

    /** The decision for one candidate on its own, before the host ceiling is applied. */
    private RecheckDecision decide(Instant now, RecheckState state) {
        if (!state.seen()) {
            return RecheckDecision.DUE;
        }
        if (claimsAnEditSinceTheLastCheck(state) && believesUpdatedClaims(state)) {
            return RecheckDecision.DUE;
        }
        Duration sinceEvent = age(now, state);
        if (sinceEvent.compareTo(windowFor(state)) > 0) {
            return RecheckDecision.RETIRED;
        }
        Duration sinceCheck = Duration.between(state.lastCheckedAt(), now);
        return sinceCheck.compareTo(intervalFor(now, state)) >= 0
                ? RecheckDecision.DUE
                : RecheckDecision.WAITING;
    }

    /**
     * How long after its last check a candidate becomes due. Public because the curve
     * is the load-bearing claim of this class and deserves to be asserted directly
     * rather than through the decision it produces.
     */
    public Duration intervalFor(Instant now, RecheckState state) {
        long seconds = (long) (age(now, state).toSeconds() * ageFactor);
        Duration scaled = Duration.ofSeconds(seconds);
        if (scaled.compareTo(minInterval) < 0) {
            return minInterval;
        }
        return scaled.compareTo(maxInterval) > 0 ? maxInterval : scaled;
    }

    /** How long after its last event a candidate stays under observation. */
    public Duration windowFor(RecheckState state) {
        return observationWindow.multipliedBy(Math.min(1 + state.changes(), maxWindowFactor));
    }

    /**
     * The longest window any candidate can earn. What a caller needs to pre-filter a
     * store of documents down to the ones this policy could still say yes to: nothing
     * whose last event is older than this can be anything but {@link
     * RecheckDecision#RETIRED}, unless a feed claims an edit -- and a feed's claims
     * arrive with the feed, not from the store.
     */
    public Duration widestWindow() {
        return observationWindow.multipliedBy(maxWindowFactor);
    }

    /**
     * Never negative. A reference instant in the future is not a data condition worth
     * a branch of its own -- it means two clocks disagree by a little -- and treating
     * it as an age of zero puts the candidate at the steep end of the curve, which is
     * where a document that was just touched belongs anyway.
     */
    private static Duration age(Instant now, RecheckState state) {
        Duration age = Duration.between(state.lastEventAt(), now);
        return age.isNegative() ? Duration.ZERO : age;
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * One decision per candidate, in the order the candidates were offered.
     *
     * @param decisions positionally aligned with the states handed to {@link #plan}
     */
    public record Plan(List<RecheckDecision> decisions) {

        public Plan {
            decisions = List.copyOf(decisions);
        }

        public RecheckDecision at(int position) {
            return decisions.get(position);
        }

        /** The positions the run should fetch, ascending. */
        public SequencedSet<Integer> due() {
            SequencedSet<Integer> due = new LinkedHashSet<>();
            for (int position = 0; position < decisions.size(); position++) {
                if (decisions.get(position) == RecheckDecision.DUE) {
                    due.add(position);
                }
            }
            return due;
        }

        public long count(RecheckDecision decision) {
            return decisions.stream().filter(value -> value == decision).count();
        }
    }
}
