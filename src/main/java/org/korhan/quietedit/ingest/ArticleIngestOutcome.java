package org.korhan.quietedit.ingest;

/**
 * Exactly one outcome per link a run planned for, so the counts add up.
 *
 * <p>{@link #NEW}, {@link #CHANGED} and {@link #UNCHANGED} are statements about content:
 * each is the version store's verdict on the text, not merely about identity.
 */
public enum ArticleIngestOutcome {

    NEW,

    /** The outcome the whole system exists to produce. */
    CHANGED,

    /** The normal result of a re-check, and by far the most common outcome of a run. */
    UNCHANGED,

    /**
     * Nothing to version and nothing wrong either: robots.txt said no, the response was a
     * binary, or the page yielded no prose. Kept apart from {@link #FAILED} so that a
     * report does not read as broken when the correct answer was "don't".
     */
    SKIPPED,

    /** The fetch failed, the URL was unusable, or the stored HTML could not be read back. */
    FAILED,

    /**
     * The run had reached its article ceiling. Not a failure and not a refusal -- the link
     * keeps its place in {@link ArticleBudget}'s order.
     */
    DEFERRED,

    /**
     * Never fetched, and never will be. Reported rather than dropped silently, because a
     * feed that starts abandoning links is a fact an operator wants to see.
     */
    ABANDONED,

    /**
     * {@link RecheckPolicy} says the document was looked at too recently, has been stable
     * long enough to be retired, or that its host has had its hour's requests; which of the
     * three is in the result's reason.
     *
     * <p>By far the most common outcome once a catalogue is warm, and the reason the others
     * stay affordable: a feed re-advertises the same thirty links every poll.
     */
    NOT_DUE
}
