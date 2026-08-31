package org.korhan.quietedit.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The re-check rule, as a function. Every property the ticket asks for is asserted
 * here rather than through a run, which is the whole reason the policy takes a clock
 * reading instead of a {@code Clock}: the interesting cases are days and weeks apart.
 *
 * <p>The two tests that carry the most weight are
 * {@link #theCurveSpendsItsAttentionOnTheHoursAfterPublicationAndThenStops}, which
 * walks one article through ten days a minute at a time and shows where the requests
 * actually land, and
 * {@link #anArticleThatKeepsChangingStaysUnderObservationLongerThanAStableOne},
 * which is the volatility criterion in both its readings -- more changes and a later
 * change each buy more time.
 */
class RecheckPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
    private static final String HOST = "news.example";

    /** The shipped settings. Their justification is in {@link RecheckPolicy}. */
    private static final RecheckPolicy POLICY = new RecheckPolicy(
            Duration.ofMinutes(10), Duration.ofHours(12), 0.25, Duration.ofDays(7), 4, 120);

    @Test
    void theIntervalIsAConstantFractionOfTheArticlesAge() {
        // Below the floor the floor wins; above the cap the cap does.
        assertThat(intervalAtAge(Duration.ofMinutes(10))).isEqualTo(Duration.ofMinutes(10));
        assertThat(intervalAtAge(Duration.ofMinutes(40))).isEqualTo(Duration.ofMinutes(10));
        assertThat(intervalAtAge(Duration.ofHours(1))).isEqualTo(Duration.ofMinutes(15));
        assertThat(intervalAtAge(Duration.ofHours(4))).isEqualTo(Duration.ofHours(1));
        assertThat(intervalAtAge(Duration.ofHours(12))).isEqualTo(Duration.ofHours(3));
        assertThat(intervalAtAge(Duration.ofDays(1))).isEqualTo(Duration.ofHours(6));
        assertThat(intervalAtAge(Duration.ofDays(2))).isEqualTo(Duration.ofHours(12));
        assertThat(intervalAtAge(Duration.ofDays(30))).isEqualTo(Duration.ofHours(12));
    }

    /**
     * The acceptance property, stated directly: the same gap since the last look means
     * "go again" for a fresh article and "not yet" for an old one.
     */
    @Test
    void aFreshArticleIsDueWhereAnOldOneIsNot() {
        Instant halfAnHourAgo = NOW.minus(Duration.ofMinutes(30));

        assertThat(decide(stable(NOW.minus(Duration.ofHours(1)), halfAnHourAgo)))
                .isEqualTo(RecheckDecision.DUE);
        assertThat(decide(stable(NOW.minus(Duration.ofDays(3)), halfAnHourAgo)))
                .isEqualTo(RecheckDecision.WAITING);
    }

    @Test
    void aCandidateNoDocumentExistsForIsAlwaysDue() {
        assertThat(decide(RecheckState.unseen(HOST))).isEqualTo(RecheckDecision.DUE);
    }

    @Test
    void aCandidateIsRetiredOnceNothingHasHappenedForItsWindow() {
        Instant yesterday = NOW.minus(Duration.ofDays(1));

        assertThat(decide(stable(NOW.minus(Duration.ofDays(6)), yesterday)))
                .isEqualTo(RecheckDecision.DUE);
        assertThat(decide(stable(NOW.minus(Duration.ofDays(8)), yesterday)))
                .isEqualTo(RecheckDecision.RETIRED);
    }

    /**
     * Both readings of the criterion. A later change buys another whole window, because
     * the window is measured from the last observed edit; more changes widen the window
     * itself. So a stable article and an edited one can never leave observation together.
     */
    @Test
    void anArticleThatKeepsChangingStaysUnderObservationLongerThanAStableOne() {
        Instant tenDaysAgo = NOW.minus(Duration.ofDays(10));
        Instant yesterday = NOW.minus(Duration.ofDays(1));

        // Discovered ten days ago and never seen to move: seven days was the whole of it.
        assertThat(decide(stable(tenDaysAgo, yesterday))).isEqualTo(RecheckDecision.RETIRED);
        // Same discovery, but edited once since: the window restarts at that edit and doubles.
        assertThat(decide(edited(NOW.minus(Duration.ofDays(20)), yesterday, tenDaysAgo, 2)))
                .isEqualTo(RecheckDecision.DUE);

        assertThat(POLICY.windowFor(stable(tenDaysAgo, yesterday))).isEqualTo(Duration.ofDays(7));
        assertThat(POLICY.windowFor(edited(tenDaysAgo, yesterday, tenDaysAgo, 2)))
                .isEqualTo(Duration.ofDays(14));
        assertThat(POLICY.windowFor(edited(tenDaysAgo, yesterday, tenDaysAgo, 3)))
                .isEqualTo(Duration.ofDays(21));
        // Capped: a page that simply changes tells us nothing more by changing again.
        assertThat(POLICY.windowFor(edited(tenDaysAgo, yesterday, tenDaysAgo, 4)))
                .isEqualTo(Duration.ofDays(28));
        assertThat(POLICY.windowFor(edited(tenDaysAgo, yesterday, tenDaysAgo, 40)))
                .isEqualTo(Duration.ofDays(28));
    }

    /**
     * An observed edit does not only extend the window, it restarts the curve: a
     * three-day-old article that was just seen to change gets a fresh article's
     * cadence, while its stable neighbour of the same age and the same last look does
     * not.
     */
    @Test
    void anObservedEditPutsAnArticleBackAtTheSteepEndOfTheCurve() {
        Instant threeDaysAgo = NOW.minus(Duration.ofDays(3));
        Instant elevenMinutesAgo = NOW.minus(Duration.ofMinutes(11));

        RecheckState justEdited = edited(threeDaysAgo, elevenMinutesAgo, elevenMinutesAgo, 2);
        assertThat(POLICY.intervalFor(NOW, justEdited)).isEqualTo(Duration.ofMinutes(10));
        assertThat(decide(justEdited)).isEqualTo(RecheckDecision.DUE);

        RecheckState neverMoved = stable(threeDaysAgo, elevenMinutesAgo);
        assertThat(POLICY.intervalFor(NOW, neverMoved)).isEqualTo(Duration.ofHours(12));
        assertThat(decide(neverMoved)).isEqualTo(RecheckDecision.WAITING);
    }

    @Test
    void aFeedClaimingAnUpdateSinceTheLastLookMakesACandidateDueAtOnce() {
        RecheckState notYetDue = stable(NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofMinutes(1)));
        assertThat(decide(notYetDue)).isEqualTo(RecheckDecision.WAITING);

        assertThat(decide(claiming(notYetDue, exact(NOW.minus(Duration.ofSeconds(30))))))
                .isEqualTo(RecheckDecision.DUE);
    }

    /** A publisher's claim is worth one look even at an article this system had dropped. */
    @Test
    void aFeedClaimingAnUpdateBringsBackARetiredCandidate() {
        RecheckState retired = stable(NOW.minus(Duration.ofDays(40)), NOW.minus(Duration.ofDays(33)));
        assertThat(decide(retired)).isEqualTo(RecheckDecision.RETIRED);

        assertThat(decide(claiming(retired, exact(NOW.minus(Duration.ofHours(1))))))
                .isEqualTo(RecheckDecision.DUE);
    }

    @Test
    void anUpdateClaimedBeforeTheLastLookIsNoReasonToLookAgain() {
        RecheckState checkedAfterTheEdit = stable(
                NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofMinutes(1)));

        assertThat(decide(claiming(checkedAfterTheEdit, exact(NOW.minus(Duration.ofHours(2))))))
                .isEqualTo(RecheckDecision.WAITING);
    }

    /**
     * A date the publisher wrote without a timezone, or without a time at all, is off
     * by hours to a day. The question being asked is "newer than a check made a minute
     * ago", so a claim that coarse is not evidence and the curve answers instead.
     */
    @Test
    void anInexactUpdateClaimIsNotActedOn() {
        RecheckState notYetDue = stable(NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofMinutes(1)));

        assertThat(decide(claiming(notYetDue, new NormalisedDate(NOW, false))))
                .isEqualTo(RecheckDecision.WAITING);
        assertThat(decide(claiming(notYetDue, NormalisedDate.ABSENT)))
                .isEqualTo(RecheckDecision.WAITING);
    }

    @Test
    void oneHostGetsNoMoreThanItsHoursWorthOfRequests() {
        RecheckPolicy policy = ceilingOf(2);
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));

        RecheckPolicy.Plan plan = policy.plan(NOW, List.of(
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(20))),
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(19))),
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(18)))));

        // All three are due on their own; the third loses to the ceiling, and it is the
        // one that has waited least.
        assertThat(plan.due()).containsExactly(0, 1);
        assertThat(plan.at(2)).isEqualTo(RecheckDecision.THROTTLED);
    }

    @Test
    void requestsAlreadyMadeWithinTheHourCountAgainstTheCeiling() {
        RecheckPolicy policy = ceilingOf(2);
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));
        RecheckState checkedTenMinutesAgo = stable(twoDaysAgo, NOW.minus(Duration.ofMinutes(10)));

        RecheckPolicy.Plan plan = policy.plan(NOW, List.of(
                checkedTenMinutesAgo,
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(20))),
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(19)))));

        assertThat(plan.at(0)).isEqualTo(RecheckDecision.WAITING);
        assertThat(plan.due()).containsExactly(1);
        assertThat(plan.at(2)).isEqualTo(RecheckDecision.THROTTLED);
    }

    @Test
    void aRequestOlderThanTheHourNoLongerCountsAgainstIt() {
        RecheckPolicy policy = ceilingOf(2);
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));

        RecheckPolicy.Plan plan = policy.plan(NOW, List.of(
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(2))),
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(20))),
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(19)))));

        assertThat(plan.at(0)).isEqualTo(RecheckDecision.WAITING);
        assertThat(plan.due()).containsExactly(1, 2);
    }

    @Test
    void theCeilingIsPerHostAndNotPerRun() {
        RecheckPolicy policy = ceilingOf(1);

        RecheckPolicy.Plan plan = policy.plan(NOW, List.of(
                RecheckState.unseen("first.example"),
                RecheckState.unseen("second.example"),
                RecheckState.unseen("second.example")));

        assertThat(plan.due()).containsExactly(0, 1);
        assertThat(plan.at(2)).isEqualTo(RecheckDecision.THROTTLED);
    }

    /**
     * A first observation cannot be taken later -- miss the original wording and it is
     * gone -- while a re-check that slips a run loses one sample of a text that is
     * still there. So the unseen candidate wins a contested slot.
     */
    @Test
    void aNeverSeenCandidateOutranksALongOverdueOneForTheLastSlot() {
        RecheckPolicy policy = ceilingOf(1);

        RecheckPolicy.Plan plan = policy.plan(NOW, List.of(
                stable(NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(20))),
                RecheckState.unseen(HOST)));

        assertThat(plan.due()).containsExactly(1);
        assertThat(plan.at(0)).isEqualTo(RecheckDecision.THROTTLED);
    }

    /** Ties go to candidate position, so two runs over unchanged state cannot disagree. */
    @Test
    void tiesAreBrokenByPositionAndThePlanIsReproducible() {
        RecheckPolicy policy = ceilingOf(1);
        Instant twoDaysAgo = NOW.minus(Duration.ofDays(2));
        List<RecheckState> states = List.of(
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(20))),
                stable(twoDaysAgo, NOW.minus(Duration.ofHours(20))));

        assertThat(policy.plan(NOW, states).decisions())
                .containsExactly(RecheckDecision.DUE, RecheckDecision.THROTTLED)
                .isEqualTo(policy.plan(NOW, states).decisions());
    }

    /**
     * The curve, simulated: one article discovered at a known instant, never edited,
     * walked minute by minute for ten days with every {@code DUE} answered by a check.
     *
     * <p>The numbers are the arithmetic of the curve rather than targets, and they are
     * what makes the case against a fixed interval concrete. Twenty-nine requests cover
     * the article's whole life: five in its first hour, sixteen in its first day and
     * twenty-one in its first three days -- and none at all after the observation window
     * closes just short of the seven-day mark. A fixed ten-minute interval, the
     * shortest this policy ever uses, would have spent 1440 requests over the same ten
     * days; a fixed interval long enough to be affordable across a catalogue would have
     * missed the first day, which is where two thirds of this article's attention went.
     *
     * <p>The bounds below are loose on purpose. What is being asserted is the shape --
     * front-loaded, bounded, terminating -- and pinning the exact count would make the
     * test fail for a deliberate change to {@code ageFactor} without saying anything
     * about whether the change was wrong.
     */
    @Test
    void theCurveSpendsItsAttentionOnTheHoursAfterPublicationAndThenStops() {
        Instant discoveredAt = NOW;
        Instant lastCheckedAt = NOW;
        List<Instant> checks = new ArrayList<>();

        for (int minute = 1; minute <= Duration.ofDays(10).toMinutes(); minute++) {
            Instant now = discoveredAt.plus(Duration.ofMinutes(minute));
            RecheckState state = new RecheckState(HOST, discoveredAt, lastCheckedAt, null, 1,
                    NormalisedDate.ABSENT);
            if (POLICY.plan(now, List.of(state)).at(0) == RecheckDecision.DUE) {
                checks.add(now);
                lastCheckedAt = now;
            }
        }

        long inTheFirstHour = checksWithin(checks, discoveredAt, Duration.ofHours(1));
        long inTheFirstDay = checksWithin(checks, discoveredAt, Duration.ofDays(1));
        long inTheFirstThreeDays = checksWithin(checks, discoveredAt, Duration.ofDays(3));

        assertThat(checks).hasSizeBetween(20, 40);
        assertThat(inTheFirstHour).isBetween(4L, 6L);
        assertThat(inTheFirstDay).isGreaterThan(checks.size() / 2);
        assertThat(inTheFirstThreeDays).isGreaterThanOrEqualTo(checks.size() * 2 / 3);
        // The window closed, so the last few days of the simulation cost nothing at all.
        assertThat(checks.getLast()).isBeforeOrEqualTo(discoveredAt.plus(Duration.ofDays(7)));
        assertThat(checksWithin(checks, discoveredAt, Duration.ofDays(7))).isEqualTo(checks.size());
    }

    /**
     * Two clocks disagreeing by a little is not a data condition worth its own branch.
     * An age of zero puts the candidate at the steep end of the curve, which is where
     * something just touched belongs.
     */
    @Test
    void aReferenceInstantInTheFutureCountsAsAgeZero() {
        RecheckState skewed = new RecheckState(HOST, NOW.plus(Duration.ofMinutes(5)),
                NOW.minus(Duration.ofHours(1)), null, 1, NormalisedDate.ABSENT);

        assertThat(POLICY.intervalFor(NOW, skewed)).isEqualTo(Duration.ofMinutes(10));
        assertThat(decide(skewed)).isEqualTo(RecheckDecision.DUE);
    }

    @Test
    void settingsThatCannotDescribeACurveAreRejected() {
        assertThatThrownBy(() -> new RecheckPolicy(Duration.ZERO, Duration.ofHours(12), 0.25,
                Duration.ofDays(7), 4, 120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecheckPolicy(Duration.ofHours(2), Duration.ofHours(1), 0.25,
                Duration.ofDays(7), 4, 120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecheckPolicy(Duration.ofMinutes(10), Duration.ofHours(12), 0,
                Duration.ofDays(7), 4, 120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecheckPolicy(Duration.ofMinutes(10), Duration.ofHours(12), 0.25,
                Duration.ofDays(7), 0, 120)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecheckPolicy(Duration.ofMinutes(10), Duration.ofHours(12), 0.25,
                Duration.ofDays(7), 4, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStateThatClaimsAHistoryItCannotHaveIsRejected() {
        assertThatThrownBy(() -> new RecheckState(HOST, null, NOW, null, 0, NormalisedDate.ABSENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecheckState(HOST, NOW, null, null, 1, NormalisedDate.ABSENT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecheckState(" ", NOW, NOW, null, 1, NormalisedDate.ABSENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Duration intervalAtAge(Duration age) {
        return POLICY.intervalFor(NOW, stable(NOW.minus(age), NOW));
    }

    private static RecheckDecision decide(RecheckState state) {
        return POLICY.plan(NOW, List.of(state)).at(0);
    }

    private static RecheckPolicy ceilingOf(int requestsPerHostPerHour) {
        return new RecheckPolicy(Duration.ofMinutes(10), Duration.ofHours(12), 0.25,
                Duration.ofDays(7), 4, requestsPerHostPerHour);
    }

    /** A document observed to say one thing only. */
    private static RecheckState stable(Instant firstSeenAt, Instant lastCheckedAt) {
        return new RecheckState(HOST, firstSeenAt, lastCheckedAt, null, 1, NormalisedDate.ABSENT);
    }

    private static RecheckState edited(Instant firstSeenAt, Instant lastCheckedAt,
                                       Instant lastChangedAt, int versionCount) {
        return new RecheckState(HOST, firstSeenAt, lastCheckedAt, lastChangedAt, versionCount,
                NormalisedDate.ABSENT);
    }

    private static RecheckState claiming(RecheckState state, NormalisedDate feedUpdated) {
        return new RecheckState(state.host(), state.firstSeenAt(), state.lastCheckedAt(),
                state.lastChangedAt(), state.versionCount(), feedUpdated);
    }

    private static NormalisedDate exact(Instant instant) {
        return new NormalisedDate(instant, true);
    }

    private static long checksWithin(List<Instant> checks, Instant from, Duration window) {
        Instant deadline = from.plus(window);
        return checks.stream().filter(check -> !check.isAfter(deadline)).count();
    }
}
