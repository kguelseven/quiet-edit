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
 * <p>Nothing else in the store records that a claim was acted on and led nowhere: a
 * document records that it was checked, not why, so from the document table a CMS
 * stamping the render time and a publisher editing constantly are both "checked often,
 * never moved".
 *
 * <p>What counts is in {@link UpdatedClaimTally}; what the count is used for is in
 * {@link RecheckPolicy}.
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
     * One query rather than a lookup per candidate: the caller decides every candidate
     * before fetching any of them.
     *
     * @return a count for every feed asked about, zero where the feed is unknown -- an
     *         unknown feed has no evidence against it and is therefore believed
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
     * One short transaction per feed, like every other write a run makes. A feed that
     * vanished between planning and writing is left alone rather than recreated: the
     * evidence is about a feed nothing will poll again.
     *
     * @return the feed's strike count after this run, or the tally's own reading of it when
     *         the feed is gone
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
