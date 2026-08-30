package org.korhan.quietedit.ingest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;

/**
 * Decides which of a run's article candidates fit inside its ceiling.
 *
 * <p>A run may not follow every link every feed advertises. The per-host gate
 * spaces same-host requests, so an uncapped run over a real catalogue is measured
 * in hours; capping it makes a run's cost predictable and, because the scheduler
 * uses a fixed delay, keeps the poll interval meaningful. What the cap must not do
 * is lose the work it declines -- so the order candidates are admitted in is the
 * load-bearing part, not the number.
 *
 * <h2>Least recently fetched first</h2>
 * A candidate is ranked by when its document was last checked, oldest first, and a
 * candidate whose document is unknown counts as infinitely old. That is what makes
 * the cap safe to apply repeatedly: everything the run defers keeps the rank that
 * got it deferred, while everything the run fetched moves to the back, so the next
 * run starts exactly where this one stopped. Taking candidates in feed order
 * instead would re-fetch the same head of the catalogue forever and never reach its
 * tail.
 *
 * <p>Ties are broken by the candidate's position, which is feed order and is itself
 * stable, so the same input always yields the same selection -- two runs over an
 * unchanged catalogue are not allowed to disagree about what they deferred.
 *
 * <h2>Known weakness</h2>
 * A candidate that is fetched but yields no document -- blocked by robots.txt, a
 * paywall stub, a binary -- stays "unknown" and therefore keeps its place at the
 * front of every following run. More than {@code maxArticles} such links on one
 * catalogue would starve everything behind them. Fixing that needs the attempt
 * itself to be remembered, which is a schema question and belongs to the re-check
 * policy rather than here.
 */
public record ArticleBudget(int maxArticles) {

    public ArticleBudget {
        if (maxArticles < 1) {
            throw new IllegalArgumentException("maxArticles must be >= 1");
        }
    }

    /**
     * Selects the candidates a run may fetch.
     *
     * @param lastCheckedAt when each candidate's document was last checked, in
     *                      candidate order; {@code null} where no document is known
     * @return the positions of the admitted candidates, ascending, so that a caller
     *         can walk its candidate list once and defer everything not named here
     */
    public SequencedSet<Integer> admit(List<Instant> lastCheckedAt) {
        List<Integer> ranked = new ArrayList<>(lastCheckedAt.size());
        for (int position = 0; position < lastCheckedAt.size(); position++) {
            ranked.add(position);
        }
        ranked.sort(Comparator
                .comparing(lastCheckedAt::get, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Comparator.naturalOrder()));

        SequencedSet<Integer> admitted = new LinkedHashSet<>();
        ranked.stream().limit(maxArticles).sorted().forEach(admitted::add);
        return admitted;
    }
}
