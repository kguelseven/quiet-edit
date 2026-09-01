package org.korhan.quietedit.ingest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/**
 * Decides which of a run's article candidates fit inside its ceiling, and which this
 * system has stopped trying.
 *
 * <p>The order candidates are admitted in is the load-bearing part, not the number:
 * ranking by when a candidate was last attempted, oldest first, is what makes the cap
 * safe to apply repeatedly, because everything deferred keeps the rank that deferred it
 * and the next run starts where this one stopped.
 *
 * <p>Attempted, not fetched: ranking by "last produced a document" would leave a link
 * that yields nothing -- robots.txt, a paywall stub, a binary -- at the front of every
 * following run and starve every real article behind it.
 *
 * <p>Ties break by candidate position, which is feed order and stable, so two runs over
 * an unchanged catalogue cannot disagree about what they deferred.
 *
 * <p>Rotation alone still costs one slot of every few runs forever, so after
 * {@code maxFailures} consecutive attempts that produced no document a candidate is
 * abandoned and a success resets the count; the choice of three strikes and the
 * permanence of abandonment are justified in quietedit-10i.4.
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
     * Everything named by neither result is deferred to the next run.
     *
     * @param history in candidate order, never null
     * @return positions into that list, ascending, so a caller can walk its candidate list
     *         once and decide every entry
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
