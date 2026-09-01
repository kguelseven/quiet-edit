package org.korhan.quietedit.ingest;

/**
 * What one run learned about one feed's {@code updated} dates: how many articles it
 * fetched while that feed's claim of an edit stood, and how many of those found the text
 * somewhere else.
 *
 * <p>A fetch counts only if the claim stood at planning time, which is
 * {@link RecheckPolicy#claimsAnEditSinceTheLastCheck} and nothing else -- a feed that
 * says nothing about an entry is neither credited nor charged for what the fetch found.
 *
 * <p>It counts whether or not the claim is why the article was fetched, and that is the
 * load-bearing part: once a feed's claims stop overriding the curve, a rule that counted
 * only claim-driven fetches would never see another one and the feed could never earn its
 * credibility back.
 *
 * <p>Only a verdict about the text counts: a paywall stub, a failed request or a first
 * observation settles nothing and is counted in neither direction.
 *
 * <p>Consecutive misses rather than a lifetime ratio, like
 * {@link AttemptHistory#failureCount}, because the question is whether this publisher's
 * dates are noise <em>now</em>.
 *
 * @param fetches   fetches made this run under a standing claim that reached a verdict
 * @param confirmed how many of them appended a revision
 */
public record UpdatedClaimTally(int fetches, int confirmed) {

    /** A feed no article was fetched for under a standing claim. */
    public static final UpdatedClaimTally NONE = new UpdatedClaimTally(0, 0);

    public UpdatedClaimTally {
        if (fetches < 0 || confirmed < 0) {
            throw new IllegalArgumentException("a tally cannot be negative");
        }
        if (confirmed > fetches) {
            throw new IllegalArgumentException("more confirmations than fetches");
        }
    }

    /** One more fetch under a standing claim, and what it found. */
    public UpdatedClaimTally plus(boolean revisionAppended) {
        return new UpdatedClaimTally(fetches + 1, confirmed + (revisionAppended ? 1 : 0));
    }

    public UpdatedClaimTally plus(UpdatedClaimTally other) {
        return new UpdatedClaimTally(fetches + other.fetches, confirmed + other.confirmed);
    }

    public boolean isEmpty() {
        return fetches == 0;
    }

    /**
     * One confirmation anywhere in the run clears the whole count: a publisher who edits
     * one article out of thirty is behaving normally, not stamping a template.
     */
    public int appliedTo(int unconfirmedClaims) {
        if (confirmed > 0) {
            return 0;
        }
        // Saturating: years of noise must not wrap around into being believed again.
        return (int) Math.min((long) unconfirmedClaims + fetches, Integer.MAX_VALUE);
    }
}
