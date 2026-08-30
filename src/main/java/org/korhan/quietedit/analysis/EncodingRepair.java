package org.korhan.quietedit.analysis;

import org.korhan.quietedit.versioning.EncodingVerdict;

import java.util.Optional;

/**
 * Decides whether the difference between two versions is explained by how their bytes
 * were decoded rather than by anyone editing the article.
 *
 * <p>The case this exists for: a publisher serves latin-1 bytes while declaring
 * {@code utf-8}. The body cannot be decoded as declared, so it is decoded with U+FFFD
 * substituted -- and from then on the mojibake is stored, hashed and diffed as if it
 * were prose. The day the publisher fixes the header, the next observation differs from
 * the previous one in every affected character, and a diff-only classifier can only
 * call that a rewrite. It is the opposite: nothing was written.
 *
 * <p>The signal is the {@code replaced} flag flipping between the two verdicts, not
 * the charset name changing. A page can move from windows-1252 to UTF-8 with both
 * decodes clean and the text byte-for-byte identical, so the charset name on its own
 * predicts nothing. The flag is a statement about whether characters were lost, which
 * is the thing that actually shows up in the diff.
 *
 * <p>Three shapes count, all of them "the decode changed":
 * <ul>
 *   <li>lossy then clean -- the repair itself, and the case named in the ticket;</li>
 *   <li>clean then lossy -- the same event running backwards, when a publisher breaks
 *       a header that used to be right. Still nobody editing anything;</li>
 *   <li>lossy both times through different charsets -- still undecodable, but failing
 *       differently, so the U+FFFD land in different places and the diff moves.</li>
 * </ul>
 *
 * <p>Two known weaknesses, both deliberate:
 * <ul>
 *   <li><b>A real edit shipped together with the repair is hidden.</b> When a publisher
 *       fixes their charset and rewrites a paragraph between the same two
 *       observations, the encoding explains part of the difference and this rule claims
 *       the whole of it. Separating the two would mean decoding the old bytes under the
 *       new charset and diffing that against the new text -- which needs the raw bytes
 *       of the earlier fetch, not just its text. The rationale therefore says that the
 *       encoding accounts for the difference, not that nothing else did, so the pair
 *       stays reviewable.</li>
 *   <li><b>A clean-to-clean charset switch is not claimed.</b> Bytes can be valid in
 *       two charsets and mean different characters in each, so a switch between two
 *       clean decodes can move the text with nobody editing it. It is not claimed here
 *       because it is indistinguishable from a genuine edit without inferring the real
 *       encoding from the bytes -- content detection, which the encoding work
 *       explicitly excluded. Such a pair is classified by its content, which errs
 *       towards reporting a change that turns out to be cosmetic rather than towards
 *       hiding one.</li>
 * </ul>
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
            // An unrecorded verdict is not evidence of a clean decode; claiming a repair
            // from a missing fact would relabel ordinary edits on older versions.
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
