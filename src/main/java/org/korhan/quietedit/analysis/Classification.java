package org.korhan.quietedit.analysis;

/**
 * How consequential a detected change is. Persisted by name, so the order of the
 * constants carries no meaning and new kinds may be appended.
 */
public enum Classification {

    /** Whitespace, punctuation, markup or boilerplate only -- the claim is unchanged. */
    COSMETIC,

    /** A factual or linguistic fix that leaves the substance of the article intact. */
    CORRECTION,

    /** The article now says something materially different. */
    SUBSTANTIVE,

    /**
     * The text moved because the page's encoding did, not because anyone edited it --
     * see {@link EncodingRepair}. Its own kind rather than a flavour of
     * {@code COSMETIC}: cosmetic means a human made a change that does not matter,
     * while this means no human changed anything at all.
     */
    ENCODING_REPAIR
}
