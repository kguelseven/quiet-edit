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
 * state and one clock reading in, one decision per candidate out.
 *
 * <p>The interval is a constant fraction of the article's age, clamped to
 * {@code [minInterval, maxInterval]}, because the rate at which a news article is still
 * being edited falls off roughly like {@code 1/age} -- so a constant fraction keeps the
 * expected number of edits missed per check roughly constant, where a tiered curve
 * would encode the same intent with five arbitrary boundaries instead of one factor.
 *
 * <p>Age and the observation window are both measured from the last observed event,
 * never from the publisher's date: a publication date is a claim and {@code firstSeenAt}
 * is a fact, and an observed edit means the article is demonstrably being worked on.
 *
 * <p>A feed's {@code updated} field decides only when to look, never whether anything
 * changed, and a feed whose claims keep leading to nothing loses that override.
 *
 * <p>The curve, the clamps, the window cap, the per-host hourly ceiling and the known
 * weaknesses are justified in quietedit-t0j and quietedit-cca.7.
 *
 * @param minInterval                politeness, not a measurement: without a floor the
 *                                   interval goes to zero as the age does
 * @param ageFactor                  the fraction of an article's age it is re-checked after
 * @param maxWindowFactor            how many windows an article's observed edits can earn it
 * @param maxUnconfirmedUpdatedClaims  how many of a feed's {@code updated} claims may lead
 *                                   to nothing in a row before they stop overriding the curve
 */
public record RecheckPolicy(
        Duration minInterval,
        Duration maxInterval,
        double ageFactor,
        Duration observationWindow,
        int maxWindowFactor,
        int maxRequestsPerHostPerHour,
        int maxUnconfirmedUpdatedClaims) {

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
     * One pass over the whole set, so that the per-host ceiling can be applied to it
     * rather than to one candidate at a time.
     *
     * @param now    passed in rather than read, which is what makes this function pure
     * @param states positions in the result line up with positions here
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
     * Never-seen candidates first, because a first observation cannot be taken later;
     * then the longest overdue, then candidate position, which is stable.
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
     * Only an exactly normalised date counts: an inexact one cannot answer a question
     * asked in minutes. Public because the run collecting evidence about a feed has to
     * ask this same question rather than a lookalike of its own.
     *
     * @throws NullPointerException if the candidate has never been checked
     */
    public static boolean claimsAnEditSinceTheLastCheck(RecheckState state) {
        NormalisedDate updated = state.feedUpdated();
        return updated.exact() && updated.instant().isAfter(state.lastCheckedAt());
    }

    /**
     * False means the claims are still read and still counted, just no longer a reason
     * to fetch at once.
     */
    public boolean believesUpdatedClaims(RecheckState state) {
        return state.unconfirmedUpdatedClaims() < maxUnconfirmedUpdatedClaims;
    }

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
     * Public because the curve is the load-bearing claim of this class and deserves to be
     * asserted directly rather than through the decision it produces.
     */
    public Duration intervalFor(Instant now, RecheckState state) {
        long seconds = (long) (age(now, state).toSeconds() * ageFactor);
        Duration scaled = Duration.ofSeconds(seconds);
        if (scaled.compareTo(minInterval) < 0) {
            return minInterval;
        }
        return scaled.compareTo(maxInterval) > 0 ? maxInterval : scaled;
    }

    /** Measured from the last event, not from the last check. */
    public Duration windowFor(RecheckState state) {
        return observationWindow.multipliedBy(Math.min(1 + state.changes(), maxWindowFactor));
    }

    /**
     * What a caller needs to pre-filter a store of documents: nothing whose last event is
     * older than this can be anything but {@link RecheckDecision#RETIRED}, unless a feed
     * claims an edit -- and a feed's claims arrive with the feed, not from the store.
     */
    public Duration widestWindow() {
        return observationWindow.multipliedBy(maxWindowFactor);
    }

    /**
     * A reference instant in the future means two clocks disagree by a little, not a data
     * condition worth its own branch; an age of zero puts the candidate at the steep end
     * of the curve, where a just-touched document belongs anyway.
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
