package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The bookkeeping half of the rule about a feed's {@code updated} dates: what one
 * run's fetches do to the feed's strike count. The reading half -- whether the count
 * is high enough to stop believing the feed -- is asserted in {@link RecheckPolicyTest}.
 */
class UpdatedClaimTallyTest {

    @Test
    void aRunInWhichNothingMovedAddsOneStrikePerFetch() {
        UpdatedClaimTally tally = UpdatedClaimTally.NONE.plus(false).plus(false).plus(false);

        assertThat(tally).isEqualTo(new UpdatedClaimTally(3, 0));
        assertThat(tally.appliedTo(0)).isEqualTo(3);
        assertThat(tally.appliedTo(17)).isEqualTo(20);
    }

    /**
     * One confirmed claim clears the record, however many of the same run's fetches
     * found nothing. A publisher who edits one article out of thirty is behaving like
     * a newsroom, not like a template.
     */
    @Test
    void oneConfirmedClaimClearsTheWholeStrikeCount() {
        UpdatedClaimTally tally = UpdatedClaimTally.NONE
                .plus(false).plus(false).plus(true).plus(false);

        assertThat(tally).isEqualTo(new UpdatedClaimTally(4, 1));
        assertThat(tally.appliedTo(4000)).isZero();
    }

    /** A run that fetched nothing under a standing claim is no evidence either way. */
    @Test
    void aRunWithoutAStandingClaimLeavesTheCountAlone() {
        assertThat(UpdatedClaimTally.NONE.isEmpty()).isTrue();
        assertThat(UpdatedClaimTally.NONE.appliedTo(12)).isEqualTo(12);
    }

    @Test
    void talliesFromTheSameFeedAddUp() {
        UpdatedClaimTally left = UpdatedClaimTally.NONE.plus(false).plus(true);
        UpdatedClaimTally right = UpdatedClaimTally.NONE.plus(false);

        assertThat(left.plus(right)).isEqualTo(new UpdatedClaimTally(3, 1));
    }

    /** A feed that has been noise for years must not wrap around into being believed. */
    @Test
    void theStrikeCountSaturatesInsteadOfOverflowing() {
        assertThat(UpdatedClaimTally.NONE.plus(false).appliedTo(Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aTallyThatCannotHaveHappenedIsRejected() {
        assertThatThrownBy(() -> new UpdatedClaimTally(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UpdatedClaimTally(1, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
