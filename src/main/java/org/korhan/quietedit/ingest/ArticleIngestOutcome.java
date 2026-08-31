package org.korhan.quietedit.ingest;

/**
 * How one article link ended in a run. Exactly one outcome per link the run
 * planned for, so the counts add up to the number of links it planned for.
 *
 * <p>{@link #NEW}, {@link #CHANGED} and {@link #UNCHANGED} are statements about
 * content, not merely about identity: each one is the version store's verdict on
 * the article's text, reached by comparing its content hash against the newest
 * stored revision.
 */
public enum ArticleIngestOutcome {

    /**
     * Fetched, prose extracted, and the canonical URL had never been seen before.
     * Its first revision was stored.
     */
    NEW,

    /**
     * A known document whose text differs from the last revision stored for it: a
     * new revision was appended. This is the outcome the whole system exists to
     * produce.
     */
    CHANGED,

    /**
     * A known document whose text is the one already on record. Nothing was
     * written, which is the normal result of a re-check and by far the most common
     * outcome of a run.
     */
    UNCHANGED,

    /**
     * Nothing to version, and nothing wrong either: robots.txt said no, the
     * response was a binary, or the page yielded no prose (a paywall stub, a
     * JavaScript shell). Kept apart from {@link #FAILED} so that a report does not
     * read as broken when the correct answer was "don't".
     */
    SKIPPED,

    /** The fetch failed, the URL was unusable, or the stored HTML could not be read back. */
    FAILED,

    /**
     * Never fetched: the run had reached its article ceiling. Not a failure and not
     * a refusal -- the link keeps its place in {@link ArticleBudget}'s order and is
     * the first thing the next run reaches for.
     */
    DEFERRED,

    /**
     * Never fetched, and never will be: the link failed to produce a document too
     * many runs in a row. Reported rather than dropped silently, because a feed that
     * starts abandoning links is a fact about the publisher an operator wants to see.
     */
    ABANDONED
}
