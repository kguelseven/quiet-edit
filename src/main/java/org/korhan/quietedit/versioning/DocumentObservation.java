package org.korhan.quietedit.versioning;

import java.time.Instant;
import java.util.UUID;

/**
 * What a document's row says about having been looked at.
 *
 * <p>A read model rather than the {@code Document} entity, because the re-check policy
 * ranks and retires thousands of documents at once and detaching entities would drag a
 * persistence context through a decision that writes nothing. It also keeps the entity's
 * setters inside this package.
 *
 * <p>Only the denormalised counters are here, which is exactly why {@link VersionStore}
 * maintains them: answering "how often has this moved" from the version table would mean
 * aggregating over every revision of every document on every run.
 *
 * @param observedOriginUrl the page the text was fetched from, already canonicalised, or
 *                          null while no run has recorded it yet
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
     * The page the text came from, not the identity it is filed under. The two are the
     * same string for a document that declares itself canonical; for a syndicated copy
     * they are two publishers' pages, and requesting the canonical id there fetches a text
     * this document never carried and reports a change no one made, in alternating
     * directions, forever.
     *
     * <p>Falls back to the canonical URL while the origin is unknown, which is what every
     * re-check did before the origin was recorded.
     */
    public String fetchUrl() {
        return observedOriginUrl == null ? canonicalUrl : observedOriginUrl;
    }
}
