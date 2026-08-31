package org.korhan.quietedit.web;

import java.util.List;

/**
 * A run of text rendered as one piece, either marked as changed or not.
 *
 * <p>The view model for word-level highlighting. The template only iterates and
 * escapes; deciding what is highlighted happens in {@link WordMarkup}, so no HTML is
 * ever assembled from article text in Java.
 */
public record Segment(String text, boolean marked) {

    /** The whole text as one unmarked run -- a paragraph shown for context. */
    public static List<Segment> plain(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(new Segment(text, false));
    }

    /** The whole text as one marked run -- a paragraph wholly added or wholly gone. */
    public static List<Segment> whole(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(new Segment(text, true));
    }
}
