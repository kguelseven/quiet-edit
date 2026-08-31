package org.korhan.quietedit.ingest;

import java.time.Instant;
import java.util.UUID;

/**
 * How much one feed has actually produced, and when it was last heard from.
 *
 * <p>A coverage figure, not a filter result: {@code documents} counts every document
 * the feed has ever produced, so a thin listing can be told apart from a stalled
 * source. Without it an empty page is ambiguous -- nothing changed and nothing was
 * fetched look identical.
 *
 * @param documents     how many documents this feed has produced, zero included: a
 *                      subscribed feed that has yielded nothing is exactly the state
 *                      worth seeing, and leaving it out would hide it
 * @param lastFetchedAt when the feed itself was last polled, null if it never has
 *                      been. Not the newest document's fetch time: a feed polled on
 *                      schedule that yields nothing new is still being fetched, and
 *                      that difference is the one an operator is looking for
 */
public record FeedCoverage(UUID id, String name, long documents, Instant lastFetchedAt) {
}
