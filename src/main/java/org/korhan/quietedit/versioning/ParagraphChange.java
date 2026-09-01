package org.korhan.quietedit.versioning;

import java.util.List;

/**
 * One paragraph-level difference between two versions. Sealed, so a consumer can switch
 * over the four kinds exhaustively and the compiler tells it when a fifth appears.
 *
 * <p>Indices are positions in the stored paragraph list, not diff-internal offsets:
 * paragraphs that fold away to nothing are skipped by the engine but still occupy their
 * index here.
 *
 * <p>{@code text} is always the original paragraph as stored: folding decides whether two
 * paragraphs are the same, but the reader is shown what the publisher wrote.
 *
 * <p>No kind carries a judgement about what the change means.
 */
public sealed interface ParagraphChange {

    /** A paragraph in the later version with no counterpart in the earlier one. */
    record Added(int toIndex, String text) implements ParagraphChange {
    }

    /** A paragraph in the earlier version with no counterpart in the later one. */
    record Removed(int fromIndex, String text) implements ParagraphChange {
    }

    /**
     * The engine pairs two paragraphs this way only when enough of their words match;
     * below that they are a removal and an addition, because a word diff of two unrelated
     * paragraphs is noise rather than detail.
     *
     * <p>A paragraph both edited and displaced is one {@code Changed}, not a {@code Moved}
     * plus a {@code Changed}; comparing the two indices tells a consumer it also moved.
     */
    record Changed(int fromIndex, int toIndex, String fromText, String toText, List<WordChange> words)
            implements ParagraphChange {
        public Changed {
            words = List.copyOf(words);
        }
    }

    /**
     * Its own kind rather than a removal plus an addition: reordering a section is a
     * different editorial act from deleting one and writing another, and a classifier that
     * saw two halves could not tell them apart.
     */
    record Moved(int fromIndex, int toIndex, String text) implements ParagraphChange {
    }
}
