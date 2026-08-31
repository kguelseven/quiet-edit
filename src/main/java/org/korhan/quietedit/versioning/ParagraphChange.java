package org.korhan.quietedit.versioning;

import java.util.List;

/**
 * One paragraph-level difference between two versions of a document. Sealed, so a
 * consumer can switch over the four kinds exhaustively and the compiler tells it
 * when a fifth appears.
 *
 * <p>Indices are positions in the stored paragraph list of the version they belong
 * to, so a renderer can point at the paragraph it means. They are not diff-internal
 * offsets: paragraphs that fold away to nothing (a block that was only an ad
 * identifier) are skipped by the engine but still occupy their index here.
 *
 * <p>{@code text} is always the original paragraph as stored, never the folded form
 * the engine compared. Folding decides <em>whether</em> two paragraphs are the same;
 * what the reader is shown is what the publisher wrote.
 *
 * <p>No kind carries a judgement about what the change means -- whether it is
 * cosmetic, a correction or a rewrite is the classifier's question.
 */
public sealed interface ParagraphChange {

    /** A paragraph in the later version with no counterpart in the earlier one. */
    record Added(int toIndex, String text) implements ParagraphChange {
    }

    /** A paragraph in the earlier version with no counterpart in the later one. */
    record Removed(int fromIndex, String text) implements ParagraphChange {
    }

    /**
     * A paragraph that survived in edited form. The engine pairs two paragraphs this
     * way only when enough of their words match; below that they are reported as a
     * removal and an addition, because a word diff of two unrelated paragraphs is
     * noise rather than detail.
     *
     * <p>{@code fromIndex} and {@code toIndex} may differ: a paragraph that was both
     * edited and displaced is one {@code Changed}, not a {@code Moved} plus a
     * {@code Changed}. Comparing the two indices tells a consumer it also moved.
     */
    record Changed(int fromIndex, int toIndex, String fromText, String toText, List<WordChange> words)
            implements ParagraphChange {
        public Changed {
            words = List.copyOf(words);
        }
    }

    /**
     * A paragraph whose text is unchanged but whose position is not. Recognised as
     * its own kind rather than as a removal plus an addition: reordering a section is
     * a different editorial act from deleting one and writing another, and a
     * classifier that saw two halves could not tell them apart.
     */
    record Moved(int fromIndex, int toIndex, String text) implements ParagraphChange {
    }
}
