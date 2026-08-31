package org.korhan.quietedit.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.ingest.ArticleContent;
import org.korhan.quietedit.versioning.ContentHasher;
import org.korhan.quietedit.versioning.DiffEngine;
import org.korhan.quietedit.versioning.DocumentDiff;
import org.korhan.quietedit.versioning.ParagraphChange;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class IndexLineRewriteTest {

    private static final String FIXTURES = "/analysis/index-line/";

    private static final String INDEX_V1 =
            "Nepal: Zahl der Vermissten steigt auf über 1300 ++ Keine Informationen zu Schweizer Opfern";
    private static final String INDEX_V2 = "Keine Informationen zu Schweizer Opfern";

    private final ContentHasher folding = new ContentHasher();
    private final DiffEngine engine = new DiffEngine(folding);
    private final IndexLineRewrite rule = new IndexLineRewrite(folding);

    @Test
    @DisplayName("dropping one entry from a joined index line is not an edit")
    void droppedEntryIsARestructure() {
        assertThat(rule.explain(INDEX_V1, INDEX_V2)).get().asString()
                .contains("index line")
                .contains("no entry was reworded");
    }

    @Test
    @DisplayName("an entry added to the index line is not an edit either")
    void addedEntryIsARestructure() {
        assertThat(rule.isIndexRewrite(INDEX_V2, INDEX_V1)).isTrue();
    }

    @Test
    @DisplayName("the same entries in a new order are a restructure")
    void reorderedEntriesAreARestructure() {
        assertThat(rule.isIndexRewrite("Erstes Ereignis ++ Zweites Ereignis",
                "Zweites Ereignis ++ Erstes Ereignis")).isTrue();
    }

    /** The one thing about this line worth reporting: the summary itself was reworded. */
    @Test
    @DisplayName("an entry reworded inside the index line is a content change")
    void rewordedEntryIsNotARestructure() {
        assertThat(rule.explain(INDEX_V1,
                "Zahl der Vermissten in Nepal steigt auf über 1400 ++ Keine Informationen zu Schweizer Opfern"))
                .isEmpty();
    }

    @Test
    @DisplayName("an index line replaced in full is not claimed, because nothing anchors it")
    void wholesaleReplacementIsNotClaimed() {
        assertThat(rule.explain("Erstes Ereignis ++ Zweites Ereignis",
                "Drittes Ereignis ++ Viertes Ereignis")).isEmpty();
    }

    @Test
    @DisplayName("a paragraph without the joiner is ordinary prose")
    void proseIsNeverAnIndexLine() {
        assertThat(rule.isIndexRewrite("Die Behörden melden über 1300 Vermisste.",
                "Die Behörden melden über 1400 Vermisste.")).isFalse();
    }

    /** The joiner needs whitespace on both sides, or every mention of C++ is a list. */
    @Test
    @DisplayName("a plus sign glued to a word does not split a line into entries")
    void gluedPlusSignIsNotTheJoiner() {
        assertThat(rule.isIndexRewrite("C++ ist eine alte und schnelle Sprache",
                "C++ ist eine alte Sprache")).isFalse();
    }

    @Test
    @DisplayName("typographic churn inside an entry still matches it item for item")
    void entriesAreComparedOnTheirFoldedForm() {
        assertThat(rule.isIndexRewrite("Erstes Ereignis ++ «Zweites» Ereignis",
                "„Zweites“  Ereignis")).isTrue();
    }

    /**
     * The acceptance case, end to end over the engine's own output: the ticker's index
     * line dropped an entry in the same revision in which an entry body was corrected.
     * The first must not reach the classifier, the second must.
     */
    @Test
    @DisplayName("a ticker re-check surfaces the corrected entry and not the index line")
    void tickerRecheckSurfacesOnlyTheGenuineEdit() {
        DocumentDiff diff = engine.diff(fixture("ticker-v1.txt"), fixture("ticker-v2.txt"));

        assertThat(diff.paragraphs()).hasSize(2);
        assertThat(rule.contentChanges(diff)).singleElement()
                .isInstanceOfSatisfying(ParagraphChange.Changed.class, changed -> {
                    assertThat(changed.fromText()).isEqualTo("Die Behörden in Kathmandu melden über 1300 Vermisste.");
                    assertThat(changed.toText()).isEqualTo("Die Behörden in Kathmandu melden über 1400 Vermisste.");
                });
    }

    /**
     * When too little of the line survives for the engine to pair the two texts itself,
     * the restructure arrives as a removal plus an addition and has to be recognised as
     * one thing.
     */
    @Test
    @DisplayName("an index rewrite split into a removal and an addition is still set aside")
    void unpairedRestructureIsSetAside() {
        DocumentDiff diff = new DocumentDiff(Optional.empty(), List.of(
                new ParagraphChange.Removed(0, "Eins ++ Zwei ++ Drei ++ Vier ++ Fuenf ++ Sechs"),
                new ParagraphChange.Added(0, "Sechs"),
                new ParagraphChange.Added(3, "Ein neuer Absatz mit echtem Inhalt.")));

        assertThat(rule.contentChanges(diff))
                .containsExactly(new ParagraphChange.Added(3, "Ein neuer Absatz mit echtem Inhalt."));
    }

    @Test
    @DisplayName("a moved paragraph is never an index rewrite")
    void movesAreLeftAlone() {
        ParagraphChange moved = new ParagraphChange.Moved(0, 2, "Eins ++ Zwei");
        assertThat(rule.contentChanges(new DocumentDiff(Optional.empty(), List.of(moved))))
                .containsExactly(moved);
    }

    /** First non-comment line is the title, every further non-blank line one paragraph. */
    private static ArticleContent fixture(String name) {
        List<String> lines = new ArrayList<>();
        for (String line : read(FIXTURES + name).lines().toList()) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                lines.add(trimmed);
            }
        }
        return new ArticleContent(lines.getFirst(), lines.subList(1, lines.size()));
    }

    private static String read(String resource) {
        try (InputStream stream = IndexLineRewriteTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
