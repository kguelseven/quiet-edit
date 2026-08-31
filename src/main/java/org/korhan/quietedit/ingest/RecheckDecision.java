package org.korhan.quietedit.ingest;

/**
 * What {@link RecheckPolicy} decided about one candidate. Exactly one per candidate,
 * so a run can walk its candidate list once and know what to do with every entry.
 */
public enum RecheckDecision {

    /**
     * Fetch it now: either the candidate has never been seen, or its interval has
     * elapsed, or its feed claims an edit since the last look.
     */
    DUE,

    /**
     * Known, still under observation, but looked at too recently. Comes back on its
     * own as soon as the interval elapses -- nothing has to remember it.
     */
    WAITING,

    /**
     * Out of the observation window: nothing has been observed to happen to this
     * document for long enough that this system stops watching it. A feed claiming an
     * edit brings it back, which is why retirement is a decision and not a deletion.
     */
    RETIRED,

    /**
     * Due, but its host has already had its hour's worth of requests. Not a refusal
     * and not a failure: the candidate keeps every reason it had for being due, so
     * the next run whose hour has room reaches it.
     */
    THROTTLED
}
