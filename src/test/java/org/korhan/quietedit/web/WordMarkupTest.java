package org.korhan.quietedit.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.korhan.quietedit.ingest.ArticleContent;
import org.korhan.quietedit.versioning.ContentHasher;
import org.korhan.quietedit.versioning.DiffEngine;
import org.korhan.quietedit.versioning.ParagraphChange;
import org.korhan.quietedit.versioning.WordChange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WordMarkupTest {

    private final DiffEngine engine = new DiffEngine(new ContentHasher());

    @Test
    @DisplayName("a replaced word is marked on both sides, the rest is not")
    void replacedWordIsMarkedOnBothSides() {
        List<WordChange> changes = List.of(new WordChange.Changed(3, 3, List.of("neuen"), List.of("geplanten")));

        assertThat(WordMarkup.before("Die Kosten der neuen Trassen steigen", changes)).containsExactly(
                new Segment("Die Kosten der", false),
                new Segment("neuen", true),
                new Segment("Trassen steigen", false));
        assertThat(WordMarkup.after("Die Kosten der geplanten Trassen steigen", changes)).containsExactly(
                new Segment("Die Kosten der", false),
                new Segment("geplanten", true),
                new Segment("Trassen steigen", false));
    }

    /**
     * Three changed words in a row are one highlighted phrase. Three segments would
     * leave the spaces between them unhighlighted, which reads as three separate edits.
     */
    @Test
    @DisplayName("consecutive changed words merge into one marked run")
    void consecutiveChangesMergeIntoOneRun() {
        List<WordChange> changes = List.of(new WordChange.Changed(1, 1,
                List.of("über", "2000", "Gemeinden"), List.of("zwei", "Drittel", "aller", "Gemeinden")));

        assertThat(WordMarkup.after("Es zwei Drittel aller Gemeinden sind gut", changes)).containsExactly(
                new Segment("Es", false),
                new Segment("zwei Drittel aller Gemeinden", true),
                new Segment("sind gut", false));
    }

    @Test
    @DisplayName("an insertion marks only the later text; the earlier one has nothing there")
    void insertionMarksOnlyTheLaterSide() {
        List<WordChange> changes = List.of(new WordChange.Added(2, List.of("erneut")));

        assertThat(WordMarkup.before("Der Bundesrat vertagt die Entscheidung", changes))
                .containsExactly(new Segment("Der Bundesrat vertagt die Entscheidung", false));
        assertThat(WordMarkup.after("Der Bundesrat erneut vertagt die Entscheidung", changes)).containsExactly(
                new Segment("Der Bundesrat", false),
                new Segment("erneut", true),
                new Segment("vertagt die Entscheidung", false));
    }

    @Test
    @DisplayName("a deletion marks only the earlier text")
    void deletionMarksOnlyTheEarlierSide() {
        List<WordChange> changes = List.of(new WordChange.Removed(1, List.of("erneut")));

        assertThat(WordMarkup.before("Bundesrat erneut vertagt", changes)).containsExactly(
                new Segment("Bundesrat", false),
                new Segment("erneut", true),
                new Segment("vertagt", false));
        assertThat(WordMarkup.after("Bundesrat vertagt", changes))
                .containsExactly(new Segment("Bundesrat vertagt", false));
    }

    @Test
    @DisplayName("a run at the very start or end of the text is marked")
    void runsAtTheEdgesAreMarked() {
        assertThat(WordMarkup.after("Zwei Drittel sind gut",
                List.of(new WordChange.Changed(0, 0, List.of("Über"), List.of("Zwei", "Drittel")))))
                .containsExactly(new Segment("Zwei Drittel", true), new Segment("sind gut", false));
        assertThat(WordMarkup.after("Zwei Drittel sind gut",
                List.of(new WordChange.Changed(3, 3, List.of("attraktiv"), List.of("gut")))))
                .containsExactly(new Segment("Zwei Drittel sind", false), new Segment("gut", true));
    }

    @Test
    @DisplayName("text with no changes is one unmarked run, and empty text is no run at all")
    void unchangedAndEmptyTexts() {
        assertThat(WordMarkup.before("Die Kosten steigen", List.of()))
                .containsExactly(new Segment("Die Kosten steigen", false));
        assertThat(WordMarkup.before("", List.of())).isEmpty();
        assertThat(WordMarkup.after(null, List.of())).isEmpty();
    }

    /**
     * An index out of range for the text cannot come from the engine, but the text and
     * the changes reach a page through separate reads. Rendering the text unmarked is a
     * worse answer than the right marks and a much better one than a 500.
     */
    @Test
    @DisplayName("an index past the end of the text marks what it can and does not fail")
    void indicesOutOfRangeAreClamped() {
        assertThat(WordMarkup.after("Zwei Drittel", List.of(new WordChange.Added(9, List.of("gut")))))
                .containsExactly(new Segment("Zwei Drittel", false));
        assertThat(WordMarkup.after("Zwei Drittel", List.of(new WordChange.Added(1, List.of("a", "b", "c")))))
                .containsExactly(new Segment("Zwei", false), new Segment("Drittel", true));
    }

    /**
     * The indices the engine really produces line up with the text it really carries.
     * The other cases here hand-build changes; this one takes the engine's own output,
     * so a change in how it tokenises cannot pass unnoticed.
     */
    @Test
    @DisplayName("the marks land on the words the engine reported for the 20min.ch subheading")
    void marksTheSubheadingCorrectionTheEngineFound() {
        ParagraphChange change = engine.diff(
                        new ArticleContent("Titel", List.of("Über 2000 Gemeinden sind attraktiv")),
                        new ArticleContent("Titel", List.of("Zwei Drittel aller Gemeinden sind gut")))
                .paragraphs().getFirst();

        assertThat(change).isInstanceOfSatisfying(ParagraphChange.Changed.class, changed -> {
            assertThat(WordMarkup.before(changed.fromText(), changed.words()))
                    .filteredOn(Segment::marked).extracting(Segment::text)
                    .containsExactly("Über 2000", "attraktiv");
            assertThat(WordMarkup.after(changed.toText(), changed.words()))
                    .filteredOn(Segment::marked).extracting(Segment::text)
                    .containsExactly("Zwei Drittel aller", "gut");
        });
    }
}
