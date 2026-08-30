package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The run's ceiling and, more importantly, the order candidates meet it in: the
 * cap is only safe if what it declines keeps coming back, which is a property of
 * the ordering rather than of the number.
 */
class ArticleBudgetTest {

    private static final Instant MONDAY = Instant.parse("2026-08-24T06:00:00Z");
    private static final Instant TUESDAY = Instant.parse("2026-08-25T06:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-08-26T06:00:00Z");

    @Test
    void admitsEverythingWhenTheRunFitsInsideTheCeiling() {
        ArticleBudget budget = new ArticleBudget(5);

        assertThat(budget.admit(Arrays.asList(null, MONDAY, null))).containsExactly(0, 1, 2);
    }

    @Test
    void neverFetchedCandidatesGoFirst() {
        ArticleBudget budget = new ArticleBudget(2);

        assertThat(budget.admit(Arrays.asList(MONDAY, null, TUESDAY, null))).containsExactly(1, 3);
    }

    @Test
    void knownCandidatesAreRankedLeastRecentlyCheckedFirst() {
        ArticleBudget budget = new ArticleBudget(2);

        assertThat(budget.admit(List.of(WEDNESDAY, MONDAY, TUESDAY))).containsExactly(1, 2);
    }

    /** Ties are feed order, so two runs over an unchanged catalogue cannot disagree. */
    @Test
    void tiesKeepFeedOrderAndTheSelectionIsReturnedInFeedOrder() {
        ArticleBudget budget = new ArticleBudget(3);

        assertThat(budget.admit(Arrays.asList(null, null, null, null))).containsExactly(0, 1, 2);
        assertThat(budget.admit(List.of(MONDAY, MONDAY, MONDAY, MONDAY))).containsExactly(0, 1, 2);
    }

    /**
     * The property the cap stands or falls on: fetching moves a candidate to the
     * back, so repeated runs walk the whole catalogue instead of re-fetching its
     * head forever.
     */
    @Test
    void repeatedRunsReachEveryCandidate() {
        ArticleBudget budget = new ArticleBudget(2);
        List<Instant> lastCheckedAt = new ArrayList<>(Arrays.asList(null, null, null, null, null));
        List<List<Integer>> runs = new ArrayList<>();

        Instant now = MONDAY;
        for (int run = 0; run < 4; run++) {
            List<Integer> fetched = List.copyOf(budget.admit(lastCheckedAt));
            for (int position : fetched) {
                lastCheckedAt.set(position, now);
            }
            runs.add(fetched);
            now = now.plusSeconds(900);
        }

        // Three runs are enough to see all five, and only then does the oldest come round again.
        assertThat(runs).containsExactly(List.of(0, 1), List.of(2, 3), List.of(0, 4), List.of(1, 2));
        assertThat(lastCheckedAt).doesNotContainNull();
    }

    @Test
    void aCeilingBelowOneIsRejected() {
        assertThatThrownBy(() -> new ArticleBudget(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
