package org.korhan.quietedit.ingest;

import java.time.Instant;

/**
 * Everything {@link RecheckPolicy} is allowed to know about one candidate.
 *
 * <p>Deliberately neither the {@code Document} entity nor the
 * {@code DocumentObservation} it is built from: the policy has to be a pure function, and
 * an entity would make it testable only with a database while the versioning read model
 * would grow it a dependency on a table it never touches.
 *
 * <p>{@code host} is a bucket key for the per-host ceiling, not a URL: the policy never
 * parses it and never requests it.
 *
 * @param firstSeenAt    null exactly when the candidate has never been seen; the age
 *                       reference rather than the publisher's date
 * @param lastCheckedAt  null exactly when {@code firstSeenAt} is
 * @param lastChangedAt  null while the document has only ever said one thing
 * @param versionCount   zero exactly when the candidate is unseen
 * @param feedUpdated    normalised rather than an instant, so the policy can see whether
 *                       the publisher's date was exact -- what an inexact claim is worth
 *                       is its call, not the caller's
 * @param unconfirmedUpdatedClaims  a property of the publisher rather than of this
 *                       candidate, and here for the same reason: the policy cannot judge
 *                       a claim without knowing what the last few were worth. Maintained
 *                       by {@link UpdatedClaimLog}
 */
public record RecheckState(
        String host,
        Instant firstSeenAt,
        Instant lastCheckedAt,
        Instant lastChangedAt,
        int versionCount,
        NormalisedDate feedUpdated,
        int unconfirmedUpdatedClaims) {

    public RecheckState {
        feedUpdated = feedUpdated == null ? NormalisedDate.ABSENT : feedUpdated;
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        if (versionCount < 0) {
            throw new IllegalArgumentException("versionCount must be >= 0");
        }
        if (unconfirmedUpdatedClaims < 0) {
            throw new IllegalArgumentException("unconfirmedUpdatedClaims must be >= 0");
        }
        if (firstSeenAt == null && (lastCheckedAt != null || lastChangedAt != null || versionCount != 0)) {
            throw new IllegalArgumentException("an unseen candidate cannot have a history");
        }
        if (firstSeenAt != null && lastCheckedAt == null) {
            throw new IllegalArgumentException("a seen candidate has always been checked at least once");
        }
    }

    /**
     * There is no re-check decision to make about a candidate no document exists for yet,
     * but it is still a request to a host, so it takes part in the ceiling.
     */
    public static RecheckState unseen(String host) {
        return new RecheckState(host, null, null, null, 0, NormalisedDate.ABSENT, 0);
    }

    public boolean seen() {
        return firstSeenAt != null;
    }

    /**
     * The most recent observed edit, or the discovery while nothing has moved. Both the
     * interval and the window are measured from here, which is what makes an edit restart
     * the clock.
     */
    public Instant lastEventAt() {
        return lastChangedAt != null && lastChangedAt.isAfter(firstSeenAt) ? lastChangedAt : firstSeenAt;
    }

    /** Observed edits. The first revision is the article, not an edit. */
    public int changes() {
        return Math.max(0, versionCount - 1);
    }
}
