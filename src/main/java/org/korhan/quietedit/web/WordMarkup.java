package org.korhan.quietedit.web;

import org.korhan.quietedit.versioning.WordChange;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns the word changes of an edited paragraph into the runs a reader sees marked.
 *
 * <p>{@link WordChange} says where a run sits in each side's token list, which a template
 * cannot iterate, so each side is walked once and neighbouring tokens with the same flag
 * are merged: three consecutive changed words are one highlighted phrase, not three.
 *
 * <p>The two sides are marked from different fields of the same change, because a change
 * does not exist in the same place on both; the earlier text carries no mark where words
 * were inserted, since there is nothing there to mark.
 *
 * <p>Tokens are re-joined with single spaces, which is the tokenisation the diff engine
 * compared on -- the indices are positions in it, and re-deriving them against the
 * original spacing is not possible from the change alone. Folding already treats any
 * whitespace run as one space, so nothing the diff would have reported is lost.
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
     * Clamped rather than trusted: the engine's indices are always in range, but a page
     * that answered a bad index with a 500 would be harder to diagnose than one that
     * renders the text unmarked.
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
