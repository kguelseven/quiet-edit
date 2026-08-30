package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The run's ceiling and, more importantly, the order candidates meet it in: the
 * cap is only safe if what it declines keeps coming back, which is a property of
 * the ordering rather than of the number.
 *
 * <p>The two starvation properties are the point of the last two tests. A link that
 * is tried but yields nothing must lose its place at the front of the queue at once,
 * and a catalogue carrying more such links than the ceiling must still work through
 * its real articles.
 */
class ArticleBudgetTest {

    private static final Instant MONDAY = Instant.parse("2026-08-24T06:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-08-25T06:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-08-26T06:00:00Z");

    @Test
    void admitsEverythingWhenTheRunFitsInsideTheCeiling() {
        ArticleBudget budget = new ArticleBudget(5, 3);

        assertThat(budget.admit(List.of(never(), tried(MONDAY), never())).admitted())
                .containsExactly(0, 1, 2);
    }

    @Test
    void neverAttemptedCandidatesGoFirst() {
        ArticleBudget budget = new ArticleBudget(2, 3);

        assertThat(budget.admit(List.of(tried(MONDAY), never(), tried(TUESDAY), never())).admitted())
                .containsExactly(1, 3);
    }

    @Test
    void knownCandidatesAreRankedLeastRecentlyAttemptedFirst() {
        ArticleBudget budget = new ArticleBudget(2, 3);

        assertThat(budget.admit(List.of(tried(WEDNESDAY), tried(MONDAY), tried(TUESDAY))).admitted())
                .containsExactly(1, 2);
    }

    /** Ties are feed order, so two runs over an unchanged catalogue cannot disagree. */
    @Test
    void tiesKeepFeedOrderAndTheSelectionIsReturnedInFeedOrder() {
        ArticleBudget budget = new ArticleBudget(3, 3);

        assertThat(budget.admit(List.of(never(), never(), never(), never())).admitted())
                .containsExactly(0, 1, 2);
        assertThat(budget.admit(List.of(tried(MONDAY), tried(MONDAY), tried(MONDAY), tried(MONDAY))).admitted())
                .containsExactly(0, 1, 2);
    }

    /**
     * The property the cap stands or falls on: attempting a candidate moves it to the
     * back, so repeated runs walk the whole catalogue instead of re-fetching its
     * head forever.
     */
    @Test
    void repeatedRunsReachEveryCandidate() {
        ArticleBudget budget = new ArticleBudget(2, 3);
        List<AttemptHistory> history = new ArrayList<>(List.of(never(), never(), never(), never(), never()));
        List<List<Integer>> runs = new ArrayList<>();

        Instant now = MONDAY;
        for (int run = 0; run < 4; run++) {
            List<Integer> fetched = List.copyOf(budget.admit(history).admitted());
            for (int position : fetched) {
                history.set(position, tried(now));
            }
            runs.add(fetched);
            now = now.plusSeconds(900);
        }

        // Three runs are enough to see all five, and only then does the oldest come round again.
        assertThat(runs).containsExactly(List.of(0, 1), List.of(2, 3), List.of(0, 4), List.of(1, 2));
        assertThat(history).noneMatch(entry -> entry.equals(never()));
    }

    /**
     * A failing candidate is ranked by <em>when</em> it was tried, not by whether the
     * try worked, so it leaves the front of the queue on its first attempt -- two runs
     * before it is abandoned.
     */
    @Test
    void aFailingCandidateStopsOutrankingNeverTriedOnes() {
        ArticleBudget budget = new ArticleBudget(1, 3);
        List<AttemptHistory> history = new ArrayList<>(
                List.of(new AttemptHistory(MONDAY, 1), never(), never()));

        assertThat(budget.admit(history).admitted()).containsExactly(1);
    }

    @Test
    void aCandidateIsAbandonedOnceItHasFailedTooOften() {
        ArticleBudget budget = new ArticleBudget(10, 3);

        ArticleBudget.Selection selection = budget.admit(List.of(
                new AttemptHistory(MONDAY, 2),
                new AttemptHistory(MONDAY, 3),
                new AttemptHistory(MONDAY, 4),
                never()));

        assertThat(selection.admitted()).containsExactly(0, 3);
        assertThat(selection.abandoned()).containsExactly(1, 2);
        assertThat(selection.deferred(0)).isFalse();
        assertThat(selection.deferred(1)).isFalse();
    }

    /** An abandoned candidate does not spend a slot the run could give a real article. */
    @Test
    void abandonedCandidatesDoNotConsumeTheCeiling() {
        ArticleBudget budget = new ArticleBudget(2, 3);

        ArticleBudget.Selection selection = budget.admit(List.of(
                new AttemptHistory(MONDAY, 3),
                new AttemptHistory(MONDAY, 3),
                never(),
                never()));

        assertThat(selection.admitted()).containsExactly(2, 3);
        assertThat(selection.abandoned()).containsExactly(0, 1);
    }

    /**
     * The acceptance property, simulated: a catalogue whose first six links can never
     * yield a document, a ceiling of two, and four real articles behind them. Six
     * broken links are three times the ceiling, so before this rule the four articles
     * would never have been reached at all.
     *
     * <p>The two numbers are the arithmetic of the rotation, not targets. Every
     * article is fetched within five runs, because a broken link goes to the back of
     * the queue the moment it is tried rather than after it is given up on. All six
     * broken links are out of the candidate set after thirteen -- six links times three
     * strikes, plus the article fetches the rotation interleaves between them.
     */
    @Test
    void aCatalogueOfMoreUnfetchableLinksThanTheCeilingStillReachesItsArticles() {
        int broken = 6;
        int real = 4;
        ArticleBudget budget = new ArticleBudget(2, 3);
        List<AttemptHistory> history = new ArrayList<>();
        for (int candidate = 0; candidate < broken + real; candidate++) {
            history.add(never());
        }
        List<Integer> fetchedByFifthRun = new ArrayList<>();

        Instant now = MONDAY;
        for (int run = 1; run <= 13; run++) {
            ArticleBudget.Selection selection = budget.admit(history);
            for (int position : selection.admitted()) {
                boolean yieldsDocument = position >= broken;
                history.set(position, new AttemptHistory(now,
                        yieldsDocument ? 0 : history.get(position).failureCount() + 1));
                if (yieldsDocument && run <= 5) {
                    fetchedByFifthRun.add(position);
                }
            }
            now = now.plusSeconds(900);
        }

        assertThat(fetchedByFifthRun).containsExactlyInAnyOrder(6, 7, 8, 9);
        assertThat(budget.admit(history).abandoned()).containsExactly(0, 1, 2, 3, 4, 5);
        // With the broken links gone, the ceiling now belongs to the articles alone.
        assertThat(budget.admit(history).admitted()).hasSize(2).allMatch(position -> position >= broken);
    }

    @Test
    void aCeilingBelowOneIsRejected() {
        assertThatThrownBy(() -> new ArticleBudget(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArticleBudget(5, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aHistoryWithFailuresButNoAttemptIsRejected() {
        assertThatThrownBy(() -> new AttemptHistory(null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AttemptHistory(MONDAY, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AttemptHistory never() {
        return AttemptHistory.NEVER;
    }

    private static AttemptHistory tried(Instant at) {
        return new AttemptHistory(at, 0);
    }
}
