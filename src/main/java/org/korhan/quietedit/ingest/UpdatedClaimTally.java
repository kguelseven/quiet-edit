package org.korhan.quietedit.ingest;

/**
 * What one run learned about one feed's {@code updated} dates: how many articles it
 * fetched while that feed's claim of an edit stood, and how many of those fetches
 * actually found the text somewhere else.
 *
 * <p>The counting rule, stated once so that both halves of it are in the same place:
 *
 * <ul>
 *   <li>A fetch counts only if the feed's claim <em>stood at planning time</em> --
 *       exactly normalised and later than the last look, which is
 *       {@link RecheckPolicy#claimsAnEditSinceTheLastCheck} and nothing else. A feed
 *       that says nothing about an entry makes no claim about it and is neither
 *       credited nor charged for what the fetch found.
 *   <li>It counts whether or not the claim is <em>why</em> the article was fetched.
 *       That is the load-bearing part: once a feed's claims stop overriding the
 *       curve, a rule that only counted claim-driven fetches would never see another
 *       one and the feed could never earn its credibility back. The curve keeps
 *       fetching those articles anyway, and every one of those fetches is still a
 *       test of the claim that was standing over it.
 *   <li>Only a verdict about the text counts. A revision confirms the claim, an
 *       unchanged text refutes it, and everything else -- robots.txt, a paywall stub,
 *       a failed request, a first observation -- settles nothing and is not counted
 *       in either direction.
 * </ul>
 *
 * <p>{@link #appliedTo} is the other half: a single confirmation clears the whole
 * strike count. Consecutive misses, like {@link AttemptHistory#failureCount}, because
 * the question is "are this publisher's dates noise <em>now</em>" -- a lifetime ratio
 * would hold a feed's first bad month against it forever, and a feed that fixes its
 * CMS on Tuesday should be believed again on Tuesday.
 *
 * @param fetches   fetches made this run under a standing claim that reached a
 *                  verdict about the text
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
     * The feed's new strike count. One confirmation anywhere in the run clears it: a
     * feed whose dates lead to real revisions is a feed worth believing, and it does
     * not matter that the same run also fetched a dozen of its entries that had not
     * moved -- a publisher who edits one article out of thirty is behaving normally,
     * not stamping a template.
     */
    public int appliedTo(int unconfirmedClaims) {
        if (confirmed > 0) {
            return 0;
        }
        // Saturating: a feed that has been noise for years must not wrap around into
        // being believed again.
        return (int) Math.min((long) unconfirmedClaims + fetches, Integer.MAX_VALUE);
    }
}
