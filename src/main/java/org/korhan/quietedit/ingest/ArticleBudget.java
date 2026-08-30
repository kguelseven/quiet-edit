package org.korhan.quietedit.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/**
 * Decides which of a run's article candidates fit inside its ceiling, and which
 * this system has stopped trying.
 *
 * <p>A run may not follow every link every feed advertises. The per-host gate
 * spaces same-host requests, so an uncapped run over a real catalogue is measured
 * in hours; capping it makes a run's cost predictable and, because the scheduler
 * uses a fixed delay, keeps the poll interval meaningful. What the cap must not do
 * is lose the work it declines -- so the order candidates are admitted in is the
 * load-bearing part, not the number.
 *
 * <h2>Least recently attempted first</h2>
 * A candidate is ranked by when it was last <em>attempted</em>, oldest first, and a
 * candidate never attempted counts as infinitely old. That is what makes the cap
 * safe to apply repeatedly: everything the run defers keeps the rank that got it
 * deferred, while everything the run tried moves to the back, so the next run starts
 * exactly where this one stopped. Taking candidates in feed order instead would
 * re-fetch the same head of the catalogue forever and never reach its tail.
 *
 * <p>Attempted, not fetched: what counts is that the run tried, not that the try
 * worked. Ranking by "last produced a document" would leave a link that yields
 * nothing -- refused by robots.txt, a paywall stub, a binary -- at the front of every
 * following run, and more than {@code maxArticles} such links on one catalogue would
 * starve every real article behind them permanently. A failing link now rotates out
 * of the front of the queue on its first attempt, long before it is given up on.
 *
 * <p>Ties are broken by the candidate's position, which is feed order and is itself
 * stable, so the same input always yields the same selection -- two runs over an
 * unchanged catalogue are not allowed to disagree about what they deferred.
 *
 * <h2>Three strikes</h2>
 * Rotation alone is not enough: an unfetchable link still costs one slot of every
 * few runs forever. After {@code maxFailures} consecutive attempts that produced no
 * document, a candidate is abandoned -- reported, but never fetched again. Three is
 * chosen against the failure this is most likely to misjudge, a transient one: a
 * publisher's outage, a rate limit, a timeout. One bad answer is normal and two are
 * plausible, so a single run must not be able to condemn a link; three consecutive
 * runs failing, spaced by the poll interval, is a claim about the link rather than
 * about the moment. A success resets the count, so a link only ever has to survive
 * one good fetch to stay a candidate.
 *
 * <p>Known weakness: abandonment is permanent. A link that becomes fetchable again
 * -- a paywall lifted, a robots.txt relaxed -- is never reconsidered, because nothing
 * re-attempts a candidate that is no longer offered. Reviving abandoned links needs
 * a policy of its own and is tracked separately.
 */
public record ArticleBudget(int maxArticles, int maxFailures) {

    public ArticleBudget {
        if (maxArticles < 1) {
            throw new IllegalArgumentException("maxArticles must be >= 1");
        }
        if (maxFailures < 1) {
            throw new IllegalArgumentException("maxFailures must be >= 1");
        }
    }

    /**
     * Splits a run's candidates into the ones it may fetch and the ones it has given
     * up on. Everything named by neither is deferred to the next run.
     *
     * @param history what is known about each candidate's earlier attempts, in
     *                candidate order and never null
     * @return positions into that list, ascending, so that a caller can walk its
     *         candidate list once and decide every entry
     */
    public Selection admit(List<AttemptHistory> history) {
        SequencedSet<Integer> abandoned = new LinkedHashSet<>();
        List<Integer> ranked = new ArrayList<>(history.size());
        for (int position = 0; position < history.size(); position++) {
            if (history.get(position).failureCount() >= maxFailures) {
                abandoned.add(position);
            } else {
                ranked.add(position);
            }
        }
        ranked.sort(Comparator
                .comparing((Integer position) -> history.get(position).lastAttemptAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Comparator.naturalOrder()));

        SequencedSet<Integer> admitted = new LinkedHashSet<>();
        ranked.stream().limit(maxArticles).sorted().forEach(admitted::add);
        return new Selection(admitted, abandoned);
    }

    /**
     * @param admitted  positions the run may fetch
     * @param abandoned positions that have failed too often to be tried again
     */
    public record Selection(SequencedSet<Integer> admitted, SequencedSet<Integer> abandoned) {

        public boolean deferred(int position) {
            return !admitted.contains(position) && !abandoned.contains(position);
        }
    }
}
