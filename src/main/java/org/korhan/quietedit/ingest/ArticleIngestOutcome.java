package org.korhan.quietedit.ingest;

/**
 * How one article link ended in a run. Exactly one outcome per link the run
 * planned for, so the counts add up to the number of links it planned for.
 *
 * <p>{@link #NEW} and {@link #UNCHANGED} are statements about <em>identity</em>,
 * not about content: this ticket's boundary stops before versioning, so a run can
 * only say whether it had seen this canonical URL before, not whether the text
 * behind it moved. Once the content hash and the version store exist, "unchanged"
 * becomes the stronger claim it sounds like -- the same article, same text -- and
 * a third outcome for "known document, changed text" joins it.
 */
public enum ArticleIngestOutcome {

    /** Fetched, prose extracted, and the canonical URL had never been seen before. */
    NEW,

    /** Fetched, prose extracted, and the document was already known. */
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
    DEFERRED
}
