package org.korhan.quietedit.versioning;

/**
 * What the version store did with one observation. Exactly one of these per call,
 * so a run's counts add up.
 */
public enum VersionOutcome {

    /** The text differs from the newest stored revision: a new version was appended. */
    APPENDED,

    /** The text is byte-identical to the newest stored revision. Nothing was written. */
    UNCHANGED,

    /**
     * The text differs from the newest revision but equals an older one: the article
     * went back to something it already said.
     *
     * <p>Nothing is written, because {@code uq_document_version_document_content} from
     * Flyway V1 allows one row per (document, content hash) and a revert would collide
     * with the revision it reverts to. Reported rather than folded into
     * {@link #UNCHANGED} so that the gap is visible instead of silent -- an article
     * that flips between two wordings is exactly the behaviour this system exists to
     * catch, and today it is the one case it cannot record. See quietedit-cca.2.
     */
    REVERTED
}
