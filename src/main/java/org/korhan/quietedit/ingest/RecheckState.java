package org.korhan.quietedit.ingest;

import java.time.Instant;

/**
 * Everything {@link RecheckPolicy} is allowed to know about one candidate: which
 * host it sits on, what this system has already observed about it, and whether its
 * feed currently claims it was edited.
 *
 * <p>Deliberately not the {@code Document} entity and deliberately not the
 * {@code DocumentObservation} it is built from. The policy is the one piece of this
 * feature that has to be a pure function -- state in, decision out -- and a policy
 * that took a JPA entity would be testable only with a database, while one that took
 * the versioning package's read model would grow a dependency on a table it never
 * touches. The cost is one mapping step in the ingest service; the benefit is that
 * every acceptance property of the policy is a plain assertion over records.
 *
 * <p>{@code host} is what the per-host ceiling is counted by. It is a bucket key,
 * not a URL: the policy never parses it and never requests it.
 *
 * @param firstSeenAt    when this system first resolved the candidate to a document,
 *                       null exactly when it has never been seen. This is the age
 *                       reference rather than the publisher's date, and the reason is
 *                       in {@link RecheckPolicy}.
 * @param lastCheckedAt  when the document was last requested, null exactly when
 *                       {@code firstSeenAt} is
 * @param lastChangedAt  when a revision was last appended, null while the document
 *                       has only ever been observed saying one thing
 * @param versionCount   revisions on record; zero exactly when the candidate is
 *                       unseen. {@code versionCount - 1} is the number of observed
 *                       edits, because the first revision is the article itself.
 * @param feedUpdated    the {@code updated} date a feed currently claims for this
 *                       entry, normalised, or {@link NormalisedDate#ABSENT} when no
 *                       feed advertises the entry or the feed omits the field. Handed
 *                       over normalised rather than as an instant so that the policy
 *                       can see whether the publisher's date was exact -- deciding
 *                       what an inexact claim is worth is the policy's call, not the
 *                       caller's.
 * @param unconfirmedUpdatedClaims  how many fetches this candidate's <em>feed</em>
 *                       has run up under a standing {@code updated} claim without a
 *                       revision coming of it, cleared by the first one that does.
 *                       A property of the publisher rather than of this candidate,
 *                       and it is here for the same reason {@code feedUpdated} is:
 *                       what a claim is worth is the policy's call, and it cannot
 *                       make that call without knowing what the last few thousand
 *                       claims from the same source were worth. Maintained by
 *                       {@link UpdatedClaimLog}.
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
     * A candidate no document exists for yet. There is no re-check decision to make
     * about it -- a first observation is not a re-check -- but it is still a request
     * to a host, so it takes part in the ceiling.
     */
    public static RecheckState unseen(String host) {
        return new RecheckState(host, null, null, null, 0, NormalisedDate.ABSENT, 0);
    }

    public boolean seen() {
        return firstSeenAt != null;
    }

    /**
     * The last thing that happened to this document: its most recent observed edit,
     * or its discovery while it has never been seen to move. Both the interval and
     * the observation window are measured from here, which is what makes an edit
     * restart the clock.
     */
    public Instant lastEventAt() {
        return lastChangedAt != null && lastChangedAt.isAfter(firstSeenAt) ? lastChangedAt : firstSeenAt;
    }

    /** Observed edits. The first revision is the article, not an edit. */
    public int changes() {
        return Math.max(0, versionCount - 1);
    }
}
