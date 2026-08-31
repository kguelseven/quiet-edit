package org.korhan.quietedit.versioning;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.ingest.ArticleContent;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiffEngineTest {

    private static final String FIXTURES = "/versioning/diff/";

    private final DiffEngine engine = new DiffEngine(new ContentHasher());

    @Test
    @DisplayName("an article compared with itself has no diff")
    void identicalVersionsProduceNothing() {
        assertThat(diff("base.txt", "base.txt").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("quotes, whitespace and a new ad slot are not an edit")
    void typographicChurnProducesNothing() {
        assertThat(diff("base.txt", "typographic-churn.txt").isEmpty()).isTrue();
    }

    @Test
    @DisplayName("one replaced word is one changed paragraph with one changed word")
    void changedWordIsReportedAtWordLevel() {
        DocumentDiff diff = diff("base.txt", "word-edit.txt");

        assertThat(diff.title()).isEmpty();
        assertThat(diff.paragraphs()).singleElement()
                .isInstanceOfSatisfying(ParagraphChange.Changed.class, changed -> {
                    assertThat(changed.fromIndex()).isEqualTo(1);
                    assertThat(changed.toIndex()).isEqualTo(1);
                    assertThat(changed.words()).singleElement()
                            .isEqualTo(new WordChange.Changed(9, 9, List.of("neuen"), List.of("geplanten")));
                });
    }

    @Test
    @DisplayName("an inserted paragraph is an addition at the position it took")
    void insertedParagraphIsAnAddition() {
        assertThat(diff("base.txt", "paragraph-added.txt").paragraphs()).singleElement()
                .isEqualTo(new ParagraphChange.Added(2,
                        "Das Bundeswirtschaftsministerium widersprach dieser Darstellung am Abend."));
    }

    @Test
    @DisplayName("a deleted paragraph is a removal at the position it held")
    void deletedParagraphIsARemoval() {
        assertThat(diff("base.txt", "paragraph-removed.txt").paragraphs()).singleElement()
                .isEqualTo(new ParagraphChange.Removed(2,
                        "„Ohne Ausgleich gibt es keine Zustimmung“, sagte die Ministerpräsidentin."));
    }

    @Test
    @DisplayName("a paragraph pulled to the top is a move, not a deletion plus an insertion")
    void reorderedParagraphIsAMove() {
        assertThat(diff("base.txt", "section-moved.txt").paragraphs()).singleElement()
                .isEqualTo(new ParagraphChange.Moved(3, 0,
                        "Eine neue Sitzung ist für den kommenden Monat angesetzt."));
    }

    @Test
    @DisplayName("a paragraph that moved and was edited is one change, not two")
    void movedAndEditedParagraphIsOneChange() {
        assertThat(diff("base.txt", "section-moved-and-edited.txt").paragraphs()).singleElement()
                .isInstanceOfSatisfying(ParagraphChange.Changed.class, changed -> {
                    assertThat(changed.fromIndex()).isEqualTo(3);
                    assertThat(changed.toIndex()).isEqualTo(0);
                    assertThat(changed.words()).singleElement()
                            .isEqualTo(new WordChange.Changed(6, 6, List.of("kommenden"), List.of("übernächsten")));
                });
    }

    @Test
    @DisplayName("a paragraph replaced by an unrelated one is a removal plus an addition")
    void unrelatedReplacementIsNotPairedAsAnEdit() {
        assertThat(diff("base.txt", "paragraph-rewritten.txt").paragraphs())
                .containsExactly(
                        new ParagraphChange.Removed(2,
                                "„Ohne Ausgleich gibt es keine Zustimmung“, sagte die Ministerpräsidentin."),
                        new ParagraphChange.Added(2,
                                "Vier Netzbetreiber kündigten am Freitag eine gemeinsame Klage an."));
    }

    @Test
    @DisplayName("a rewritten headline is reported apart from the body")
    void headlineChangeIsReportedSeparately() {
        DocumentDiff diff = diff("base.txt", "headline-rewritten.txt");

        assertThat(diff.paragraphs()).isEmpty();
        assertThat(diff.title()).get().satisfies(title -> {
            assertThat(title.fromTitle()).isEqualTo("Bundesrat vertagt die Entscheidung zum Netzausbau");
            assertThat(title.toTitle()).isEqualTo("Bundesrat blockiert die Entscheidung zum Netzausbau");
            assertThat(title.words()).singleElement()
                    .isEqualTo(new WordChange.Changed(1, 1, List.of("vertagt"), List.of("blockiert")));
        });
    }

    @Test
    @DisplayName("a page that gains a headline changes from the empty title, not from nothing")
    void absentHeadlineIsTheEmptyString() {
        DocumentDiff diff = engine.diff(
                new ArticleContent("", List.of("Der Entwurf liegt vor.")),
                new ArticleContent("Entwurf vorgelegt", List.of("Der Entwurf liegt vor.")));

        assertThat(diff.title()).get().satisfies(title -> {
            assertThat(title.fromTitle()).isEmpty();
            assertThat(title.words()).singleElement()
                    .isEqualTo(new WordChange.Added(0, List.of("Entwurf", "vorgelegt")));
        });
    }

    @Test
    @DisplayName("half the words surviving is enough to pair two paragraphs as an edit")
    void similarityAtTheThresholdPairs() {
        assertThat(diffOfSingleParagraph("eins zwei drei vier", "eins zwei fuenf sechs"))
                .singleElement().isInstanceOf(ParagraphChange.Changed.class);
    }

    @Test
    @DisplayName("below the threshold the two paragraphs are not claimed to be related")
    void similarityBelowTheThresholdDoesNotPair() {
        assertThat(diffOfSingleParagraph("eins zwei drei vier", "eins sieben acht neun"))
                .hasSize(2)
                .hasAtLeastOneElementOfType(ParagraphChange.Removed.class)
                .hasAtLeastOneElementOfType(ParagraphChange.Added.class);
    }

    @Test
    @DisplayName("the diff of A to B is not the diff of B to A")
    void diffingIsDirectional() {
        DocumentDiff forwards = diff("base.txt", "paragraph-added.txt");
        DocumentDiff backwards = diff("paragraph-added.txt", "base.txt");

        assertThat(backwards).isNotEqualTo(forwards);
        assertThat(forwards.paragraphs()).allMatch(ParagraphChange.Added.class::isInstance);
        assertThat(backwards.paragraphs()).allMatch(ParagraphChange.Removed.class::isInstance);
    }

    @Test
    @DisplayName("a reordering read backwards is still a move, in the other direction")
    void movesAreDirectionalToo() {
        assertThat(diff("section-moved.txt", "base.txt").paragraphs()).singleElement()
                .isEqualTo(new ParagraphChange.Moved(0, 3,
                        "Eine neue Sitzung ist für den kommenden Monat angesetzt."));
    }

    @Test
    @DisplayName("the same pair of versions always yields the same diff")
    void diffingIsDeterministic() {
        ArticleContent from = fixture("base.txt");
        ArticleContent to = fixture("section-moved-and-edited.txt");
        assertThat(engine.diff(from, to)).isEqualTo(engine.diff(from, to));
    }

    /**
     * A repeated paragraph is the case the move pass cannot resolve; this pins that it
     * still reports the right set of changes rather than falling apart.
     */
    @Test
    @DisplayName("one of two identical paragraphs being deleted is reported as one removal")
    void repeatedParagraphsStillYieldOneRemoval() {
        List<String> twice = List.of("Der Entwurf liegt vor.", "Die Laender fordern Ausgleich.",
                "Der Entwurf liegt vor.");
        DocumentDiff diff = engine.diff(
                new ArticleContent("Titel", twice),
                new ArticleContent("Titel", twice.subList(0, 2)));

        assertThat(diff.paragraphs()).singleElement().isInstanceOf(ParagraphChange.Removed.class);
    }

    private List<ParagraphChange> diffOfSingleParagraph(String from, String to) {
        return engine.diff(new ArticleContent("Titel", List.of(from)),
                new ArticleContent("Titel", List.of(to))).paragraphs();
    }

    private DocumentDiff diff(String from, String to) {
        return engine.diff(fixture(from), fixture(to));
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
        if (lines.isEmpty()) {
            throw new IllegalStateException("empty fixture: " + name);
        }
        return new ArticleContent(lines.getFirst(), lines.subList(1, lines.size()));
    }

    private static String read(String resource) {
        try (InputStream stream = DiffEngineTest.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("missing fixture: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
