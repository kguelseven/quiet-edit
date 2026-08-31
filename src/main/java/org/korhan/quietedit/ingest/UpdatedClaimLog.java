package org.korhan.quietedit.ingest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers whether a feed's {@code updated} dates have been telling the truth.
 *
 * <p>This exists because nothing else in the store records that a claim was acted on
 * and led nowhere. A document only records that it was checked, not <em>why</em>, so
 * a publisher whose CMS stamps the render time into {@code updated} looks exactly
 * like a publisher whose articles are being edited constantly -- from the document
 * table both are "checked often, never moved".
 *
 * <p>The counter it maintains is a strike count on the feed row: consecutive fetches
 * made under a standing claim that found the text unchanged, cleared by any such
 * fetch that appends a revision. What counts and why is in {@link UpdatedClaimTally};
 * what the count is then used for is in {@link RecheckPolicy}.
 *
 * <p>Read before a run plans anything, written once the run's outcomes are known.
 */
@Service
public class UpdatedClaimLog {

    private final FeedRepository feeds;

    public UpdatedClaimLog(FeedRepository feeds) {
        this.feeds = feeds;
    }

    /**
     * The strike count of each of these feeds.
     *
     * <p>One query rather than a lookup per candidate: the caller is the ingest run's
     * planner, which decides every candidate before it fetches any of them, and a
     * run's candidates come from a handful of feeds however many articles they carry.
     *
     * @return a count for every feed asked about, zero where the feed is unknown --
     *         an unknown feed has no evidence against it and is therefore believed
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> unconfirmedClaimsOf(Collection<UUID> feedIds) {
        Map<UUID, Integer> claims = new HashMap<>(feedIds.size());
        for (UUID feedId : feedIds) {
            claims.put(feedId, 0);
        }
        for (Feed feed : feeds.findAllById(feedIds)) {
            claims.put(feed.getId(), feed.getUnconfirmedUpdatedClaims());
        }
        return claims;
    }

    /**
     * Folds one run's evidence about one feed into its strike count.
     *
     * <p>One short transaction per feed, like every other write an ingest run makes:
     * a run spends nearly all of its wall clock in network I/O, and a transaction
     * spanning it would pin a connection for all of it. A feed that vanished between
     * planning and writing is left alone rather than recreated -- the evidence is
     * about a feed nothing will poll again.
     *
     * @return the feed's strike count after this run, or the tally's own reading of
     *         it when the feed is gone
     */
    @Transactional
    public int record(UUID feedId, UpdatedClaimTally tally) {
        Optional<Feed> existing = feeds.findById(feedId);
        if (existing.isEmpty()) {
            return tally.appliedTo(0);
        }
        Feed feed = existing.get();
        int unconfirmed = tally.appliedTo(feed.getUnconfirmedUpdatedClaims());
        feed.setUnconfirmedUpdatedClaims(unconfirmed);
        feeds.save(feed);
        return unconfirmed;
    }
}
