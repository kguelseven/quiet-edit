package org.korhan.quietedit.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.versioning.CharsetSource;
import org.korhan.quietedit.versioning.EncodingVerdict;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule that keeps a publisher fixing their charset header out of the change feed
 * as a rewrite. Each case is a pair of verdicts, because the rule reads nothing else --
 * no text, no diff -- by design: the decision is made from what was certain at fetch
 * time.
 */
class EncodingRepairTest {

    private static final EncodingVerdict LOSSY_UTF8 =
            new EncodingVerdict("UTF-8", CharsetSource.DOCUMENT, true);
    private static final EncodingVerdict CLEAN_WINDOWS_1252 =
            new EncodingVerdict("windows-1252", CharsetSource.HTTP_HEADER, false);
    private static final EncodingVerdict CLEAN_UTF8 =
            new EncodingVerdict("UTF-8", CharsetSource.HTTP_HEADER, false);

    @Test
    @DisplayName("mojibake replaced by readable text is a repair, not an edit")
    void aFixedCharsetHeaderIsARepair() {
        assertThat(EncodingRepair.isEncodingRepair(LOSSY_UTF8, CLEAN_WINDOWS_1252)).isTrue();
        assertThat(EncodingRepair.explain(LOSSY_UTF8, CLEAN_WINDOWS_1252)).get().asString()
                .contains("The decode changed, not the article")
                .contains("the previous text was decoded with replacement characters and this one was not")
                .contains("windows-1252 (HTTP Content-Type)")
                .contains("UTF-8 (document declaration), with replacement characters");
    }

    @Test
    @DisplayName("a header that breaks is the same event backwards, still not an edit")
    void aBrokenCharsetHeaderIsAlsoNotAnEdit() {
        assertThat(EncodingRepair.explain(CLEAN_WINDOWS_1252, LOSSY_UTF8)).get().asString()
                .contains("the previous text decoded cleanly and this one needed replacement characters");
    }

    @Test
    @DisplayName("still undecodable, but through a different charset, still moves the text")
    void lossyThroughTwoDifferentCharsetsCounts() {
        EncodingVerdict lossyUtf16 = new EncodingVerdict("UTF-16", CharsetSource.HTTP_HEADER, true);

        assertThat(EncodingRepair.explain(LOSSY_UTF8, lossyUtf16)).get().asString()
                .contains("both texts needed replacement characters, but through different charsets");
    }

    @Test
    @DisplayName("the same lossy decode twice explains nothing: the loss is identical")
    void twiceTheSameLossyDecodeIsNotARepair() {
        assertThat(EncodingRepair.explain(LOSSY_UTF8, LOSSY_UTF8)).isEmpty();
    }

    /**
     * The documented weakness, asserted so that it stays a decision rather than
     * drifting into a surprise: two clean decodes are classified by their content even
     * when the charset moved, because telling a re-encoding from an edit there needs the
     * content detection the encoding work excluded.
     */
    @Test
    @DisplayName("a switch between two clean decodes is left to the content")
    void aCleanCharsetSwitchIsNotClaimed() {
        assertThat(EncodingRepair.explain(CLEAN_WINDOWS_1252, CLEAN_UTF8)).isEmpty();
        assertThat(EncodingRepair.explain(CLEAN_UTF8, CLEAN_UTF8)).isEmpty();
    }

    @Test
    @DisplayName("an unrecorded verdict is not evidence of a clean decode")
    void anUnrecordedVerdictClaimsNothing() {
        assertThat(EncodingRepair.explain(null, CLEAN_UTF8)).isEmpty();
        assertThat(EncodingRepair.explain(LOSSY_UTF8, null)).isEmpty();
        assertThat(EncodingRepair.explain(null, null)).isEmpty();
    }
}
