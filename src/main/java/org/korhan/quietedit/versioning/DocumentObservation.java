package org.korhan.quietedit.versioning;

import java.time.Instant;
import java.util.UUID;

/**
 * What a document's row says about having been looked at: its identity, where its
 * text was read, when it was discovered, when it was last requested, and what its
 * history amounts to.
 *
 * <p>A read model rather than the {@code Document} entity, and the reason is the
 * caller: the re-check policy ranks and retires thousands of documents at once, so
 * detaching entities for it would drag a persistence context through a decision that
 * writes nothing. It is also the boundary that keeps the entity's setters inside this
 * package.
 *
 * <p>Only the denormalised counters are here -- no version rows. That is exactly why
 * {@code versionCount} and {@code lastChangedAt} are maintained on the document by
 * {@link VersionStore}: answering "how often has this moved, and when last" from the
 * version table would mean aggregating over every revision of every document on every
 * run.
 *
 * @param canonicalUrl      identity: which article this is
 * @param observedOriginUrl the page the text was fetched from, already canonicalised,
 *                          or null while no run has recorded it yet
 */
public record DocumentObservation(
        UUID documentId,
        String canonicalUrl,
        String observedOriginUrl,
        UUID feedId,
        Instant firstSeenAt,
        Instant lastCheckedAt,
        Instant lastChangedAt,
        int versionCount) {

    /**
     * The URL to ask for when this document is looked at again -- the page the text
     * came from, not the identity it is filed under.
     *
     * <p>For a document that declares itself canonical the two are the same string,
     * which is nearly all of them. For a syndicated copy they are two different
     * publishers' pages, and requesting the canonical id there fetches a text this
     * document has never been observed to carry: the comparison against the newest
     * revision then reports a change no one made, and it does so again in the other
     * direction on the next run, forever.
     *
     * <p>Falls back to the canonical URL while the origin is unknown, which is what
     * every re-check did before the origin was recorded at all.
     */
    public String fetchUrl() {
        return observedOriginUrl == null ? canonicalUrl : observedOriginUrl;
    }
}
