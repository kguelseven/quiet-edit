package org.korhan.quietedit.web;

import org.korhan.quietedit.versioning.WordChange;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns the word changes of an edited paragraph into the runs a reader sees marked.
 *
 * <p>{@link WordChange} says where a run sits in the token list of each side. That is
 * what a renderer needs, but not what a template can iterate: a template cannot ask
 * "is token 9 part of a change". So each side is walked once, the tokens covered by a
 * change are flagged, and neighbouring tokens with the same flag are merged into one
 * {@link Segment}. Merging matters for the result rather than for performance -- three
 * consecutive changed words are one highlighted phrase, not three highlighted words
 * with unhighlighted gaps between them.
 *
 * <h2>Which changes mark which side</h2>
 * The two sides are marked from different fields of the same change, because a change
 * does not exist in the same place on both. A removal marks only the earlier text, an
 * addition only the later one, and a replacement marks its own run on each side. The
 * earlier text therefore carries no mark where words were inserted: there is nothing
 * there to mark, and the inserted words are marked on the side they arrived on.
 *
 * <h2>Whitespace</h2>
 * Tokens are re-joined with single spaces, so a paragraph stored with a double space
 * or a line break inside it renders with one. That is the same tokenisation the diff
 * engine compares on -- the indices in a {@link WordChange} are positions in it, and
 * re-deriving them against the original spacing is not possible from the change alone.
 * Since folding already treats any whitespace run as one space, no difference is lost
 * that the diff would have reported.
 */
public final class WordMarkup {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private WordMarkup() {
    }

    /** The earlier spelling, with the words that were replaced or dropped marked. */
    public static List<Segment> before(String text, List<WordChange> changes) {
        List<String> tokens = tokens(text);
        boolean[] marked = new boolean[tokens.size()];
        for (WordChange change : changes) {
            switch (change) {
                case WordChange.Added ignored -> {
                    // Nothing was there to mark; the arriving words are marked on the later side.
                }
                case WordChange.Removed removed -> mark(marked, removed.fromIndex(), removed.words().size());
                case WordChange.Changed replaced ->
                        mark(marked, replaced.fromIndex(), replaced.fromWords().size());
            }
        }
        return segments(tokens, marked);
    }

    /** The later spelling, with the words that arrived or replaced others marked. */
    public static List<Segment> after(String text, List<WordChange> changes) {
        List<String> tokens = tokens(text);
        boolean[] marked = new boolean[tokens.size()];
        for (WordChange change : changes) {
            switch (change) {
                case WordChange.Added added -> mark(marked, added.toIndex(), added.words().size());
                case WordChange.Removed ignored -> {
                    // The words are gone from this side; they are marked on the earlier one.
                }
                case WordChange.Changed replaced -> mark(marked, replaced.toIndex(), replaced.toWords().size());
            }
        }
        return segments(tokens, marked);
    }

    /**
     * Clamped rather than trusted. The engine's indices are always in range for the
     * text they came from, but a page that answered a bad index with a 500 would be
     * harder to diagnose than one that renders the text unmarked.
     */
    private static void mark(boolean[] marked, int start, int length) {
        int from = (int) Math.clamp(start, 0, marked.length);
        int to = (int) Math.clamp((long) start + length, from, marked.length);
        for (int i = from; i < to; i++) {
            marked[i] = true;
        }
    }

    private static List<Segment> segments(List<String> tokens, boolean[] marked) {
        List<Segment> segments = new ArrayList<>();
        int start = 0;
        while (start < tokens.size()) {
            int end = start + 1;
            while (end < tokens.size() && marked[end] == marked[start]) {
                end++;
            }
            segments.add(new Segment(String.join(" ", tokens.subList(start, end)), marked[start]));
            start = end;
        }
        return List.copyOf(segments);
    }

    private static List<String> tokens(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.isEmpty() ? List.of() : List.of(WHITESPACE.split(stripped));
    }
}
