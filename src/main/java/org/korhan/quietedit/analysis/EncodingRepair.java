package org.korhan.quietedit.analysis;

import org.korhan.quietedit.versioning.EncodingVerdict;

import java.util.Optional;

/**
 * Decides whether the difference between two versions is explained by how their bytes
 * were decoded rather than by anyone editing the article.
 *
 * <p>The case this exists for: a publisher serving latin-1 bytes while declaring
 * {@code utf-8} has its mojibake stored, hashed and diffed as if it were prose, so the
 * day the header is fixed a diff-only classifier can only call the result a rewrite.
 *
 * <p>The signal is the {@code replaced} flag flipping, not the charset name changing: a
 * page can move from windows-1252 to UTF-8 with both decodes clean and the text
 * identical, so the name alone predicts nothing.
 *
 * <p>Three shapes count -- lossy then clean, clean then lossy, and lossy both times
 * through different charsets -- because all three mean the decode changed.
 *
 * <p>The rationale says the encoding accounts for the difference, not that nothing else
 * did, so a real edit shipped with the repair leaves the pair reviewable; a
 * clean-to-clean charset switch is not claimed at all, because telling it from an edit
 * needs content detection; both weaknesses are justified in quietedit-10i.7.
 */
public final class EncodingRepair {

    private EncodingRepair() {
    }

    /**
     * @param from the earlier version's verdict, null when it did not record one
     * @param to   the later version's verdict, null when it did not record one
     * @return the rationale for classifying the pair as {@link
     *         Classification#ENCODING_REPAIR}, or empty when the difference is not
     *         attributable to the decode
     */
    public static Optional<String> explain(EncodingVerdict from, EncodingVerdict to) {
        if (from == null || to == null) {
            // An unrecorded verdict is not evidence of a clean decode, only a missing fact.
            return Optional.empty();
        }
        if (to.lossFlippedFrom(from)) {
            String direction = from.replaced()
                    ? "the previous text was decoded with replacement characters and this one was not"
                    : "the previous text decoded cleanly and this one needed replacement characters";
            return Optional.of(rationale(direction, from, to));
        }
        if (from.replaced() && !from.charset().equals(to.charset())) {
            return Optional.of(rationale(
                    "both texts needed replacement characters, but through different charsets", from, to));
        }
        return Optional.empty();
    }

    /** True when {@link #explain} has something to say. */
    public static boolean isEncodingRepair(EncodingVerdict from, EncodingVerdict to) {
        return explain(from, to).isPresent();
    }

    /**
     * Names both verdicts, because "the encoding changed" is only reviewable if the
     * reader can see which way.
     */
    private static String rationale(String direction, EncodingVerdict from, EncodingVerdict to) {
        return "The decode changed, not the article: %s. Decoded %s, previously %s. "
                .formatted(direction, to.describe(), from.describe())
                + "The encoding accounts for the difference; an edit made in the same window would be hidden by it.";
    }
}
