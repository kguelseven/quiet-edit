package org.korhan.quietedit.ingest;

import java.time.Instant;

/**
 * What this system knows about its previous tries at one article link: when it last
 * tried, and how many consecutive tries failed to produce a document.
 *
 * <p>Two facts rather than one, because ranking and giving up are different
 * questions. {@code lastAttemptAt} is what moves a link out of the front of the
 * budget's queue the moment it is tried, whether that try worked or not;
 * {@code failureCount} is what eventually takes it out of the queue for good.
 *
 * @param lastAttemptAt null exactly when this link has never been attempted
 * @param failureCount  consecutive failures, reset to zero by a success
 */
public record AttemptHistory(Instant lastAttemptAt, int failureCount) {

    /** A link this system has never tried. Ranks ahead of every attempted link. */
    public static final AttemptHistory NEVER = new AttemptHistory(null, 0);

    public AttemptHistory {
        if (failureCount < 0) {
            throw new IllegalArgumentException("failureCount must be >= 0");
        }
        if (lastAttemptAt == null && failureCount != 0) {
            throw new IllegalArgumentException("a link that was never attempted cannot have failures");
        }
    }

    public boolean attempted() {
        return lastAttemptAt != null;
    }
}
