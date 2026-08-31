package org.korhan.quietedit.versioning;

/**
 * What the version store did with one observation. Exactly one of these per call,
 * so a run's counts add up.
 */
public enum VersionOutcome {

    /** The text differs from the newest stored revision: a new version was appended. */
    APPENDED,

    /**
     * The text is byte-identical to the newest stored revision. Nothing was written.
     *
     * <p>Newest only. Text that matches an <em>older</em> revision is a change like any
     * other and is appended: an article that goes back to what it said yesterday moved
     * twice, and folding the second move in here would hide exactly the behaviour this
     * system exists to catch.
     */
    UNCHANGED
}
