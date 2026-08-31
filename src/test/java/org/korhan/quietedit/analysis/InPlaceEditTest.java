package org.korhan.quietedit.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.ingest.ArticleContent;
import org.korhan.quietedit.versioning.ContentHasher;
import org.korhan.quietedit.versioning.DiffEngine;
import org.korhan.quietedit.versioning.DocumentDiff;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InPlaceEditTest {

    private static final String IN_PLACE = "/analysis/in-place/";
    private static final String DIFFS = "/versioning/diff/";

    private final ContentHasher folding = new ContentHasher();
    private final DiffEngine engine = new DiffEngine(folding);
    private final InPlaceEdit rule = new InPlaceEdit(new IndexLineRewrite(folding));

    /**
     * The case the filter exists for. A ticker that gained an entry also rolls its own
     * index line, so the raw diff carries a paragraph edited in place -- judged on that
     * alone, no ticker revision would ever be filtered out.
     */
    @Test
    @DisplayName("a ticker that gained an entry only added, index line included")
    void appendedTickerEntryIsNotARewrite() {
        assertThat(rule.rewritesExistingText(
                diff(IN_PLACE, "ticker-appended-v1.txt", "ticker-appended-v2.txt"))).isFalse();
    }

    /**
     * The same revision plus one corrected number. The filter has to keep this one:
     * everything else about the two revisions is identical, so the correction is the
     * only thing that can decide it.
     */
    @Test
    @DisplayName("a ticker that also corrected an entry rewrote existing text")
    void correctedTickerEntryIsARewrite() {
        assertThat(rule.rewritesExistingText(
                diff(IN_PLACE, "ticker-appended-v1.txt", "ticker-corrected-v2.txt"))).isTrue();
    }

    /** The goldset case: the first silent edit the system found in the wild. */
    @Test
    @DisplayName("the 20min.ch correction rewrote existing text")
    void wohnattraktivitaetCorrectionIsARewrite() {
        assertThat(rule.rewritesExistingText(diff(DIFFS,
                "wohnattraktivitaet-published.txt", "wohnattraktivitaet-corrected.txt"))).isTrue();
    }

    @Test
    @DisplayName("a headline rewritten over an untouched body is a rewrite")
    void changedHeadlineIsARewrite() {
        assertThat(rule.rewritesExistingText(diff(DIFFS, "base.txt", "headline-rewritten.txt"))).isTrue();
    }

    @Test
    @DisplayName("a paragraph inserted into an article is not a rewrite")
    void insertedParagraphIsNotARewrite() {
        assertThat(rule.rewritesExistingText(diff(DIFFS, "base.txt", "paragraph-added.txt"))).isFalse();
    }

    @Test
    @DisplayName("a deleted paragraph is a rewrite even though nothing was written")
    void deletedParagraphIsARewrite() {
        assertThat(rule.rewritesExistingText(diff(DIFFS, "base.txt", "paragraph-removed.txt"))).isTrue();
    }

    @Test
    @DisplayName("a reordered paragraph is a rewrite: the article stopped saying it there")
    void movedParagraphIsARewrite() {
        assertThat(rule.rewritesExistingText(diff(DIFFS, "base.txt", "section-moved.txt"))).isTrue();
    }

    @Test
    @DisplayName("two revisions that say the same thing rewrote nothing")
    void emptyDiffIsNotARewrite() {
        assertThat(rule.rewritesExistingText(diff(DIFFS, "base.txt", "typographic-churn.txt"))).isFalse();
    }

    private DocumentDiff diff(String directory, String from, String to) {
        return engine.diff(fixture(directory + from), fixture(directory + to));
    }

    /** First non-comment line is the title, every further non-blank line one paragraph. */
    private static ArticleContent fixture(String resource) {
        List<String> lines = new ArrayList<>();
        for (String line : read(resource).lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("empty fixture: " + resource);
        }
        return new ArticleContent(lines.getFirst(), lines.subList(1, lines.size()));
    }

    private static String read(String resource) {
        try (InputStream stream = InPlaceEditTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
