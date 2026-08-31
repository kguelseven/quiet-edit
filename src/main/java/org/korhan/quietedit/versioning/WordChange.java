package org.korhan.quietedit.versioning;

import java.util.List;

/**
 * One run of words that differs between two spellings of the same paragraph or
 * title. Produced only inside a {@link ParagraphChange.Changed} or a
 * {@link TitleChange} -- a paragraph that was wholly added, removed or moved has
 * no word-level detail worth carrying.
 *
 * <p>Words are whitespace-separated tokens of the <em>original</em> text, so a
 * consumer can render them as the publisher spelled them. Whether two tokens are
 * the same word is decided on their folded form, which is why a paragraph whose
 * only difference is a curly quote produces no {@code WordChange} at all.
 *
 * <p>Punctuation stays attached to its word. Splitting it off would report
 * "sagte" -> "sagte" plus "." -> "," for a changed sentence ending, which is the
 * same information spread over two entries; a consumer that wants character-level
 * detail can diff the two tokens itself.
 *
 * <p>Indices are positions in the token lists of the two texts, not character
 * offsets: they say where in the sequence the run sits, which is what a renderer
 * walking both token lists needs.
 */
public sealed interface WordChange {

    /** Words present in the later text only. {@code toIndex} is where they begin in it. */
    record Added(int toIndex, List<String> words) implements WordChange {
        public Added {
            words = List.copyOf(words);
        }
    }

    /** Words present in the earlier text only. {@code fromIndex} is where they began in it. */
    record Removed(int fromIndex, List<String> words) implements WordChange {
        public Removed {
            words = List.copyOf(words);
        }
    }

    /** A run replaced by another run. Both sides are non-empty; either may be longer. */
    record Changed(int fromIndex, int toIndex, List<String> fromWords, List<String> toWords)
            implements WordChange {
        public Changed {
            fromWords = List.copyOf(fromWords);
            toWords = List.copyOf(toWords);
        }
    }
}
