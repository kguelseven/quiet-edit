package org.korhan.quietedit.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One diff, as the page renders it: the two revisions it is of, the headline change,
 * the paragraph changes, and the other pairs of this document that can be opened.
 *
 * @param title      the headline change, or null when both revisions agree on it. Its
 *                   own field rather than the first paragraph row, because a retitled
 *                   article is its own editorial event and the page calls it out above
 *                   the body
 * @param unchanged  true when the two revisions say the same thing, which can happen
 *                   for a pair stored for another reason -- a re-decode that folds away
 * @param pairs      every adjacent pair of this document, so a reader can walk the
 *                   history without going back to the index
 */
public record DiffPageView(
        UUID documentId,
        String canonicalUrl,
        String host,
        String headline,
        Revision from,
        Revision to,
        boolean unchanged,
        TitleDiff title,
        List<Paragraph> paragraphs,
        List<Pair> pairs) {

    /**
     * @param encoding how the bytes were decoded, as prose; null on a revision written
     *                 before the verdict was recorded. Shown because an edit that is
     *                 really a re-decode is the first thing to rule out
     */
    public record Revision(
            int number,
            Instant fetchedAt,
            Instant publishedAt,
            int paragraphs,
            String contentHash,
            String encoding) {
    }

    public record TitleDiff(List<Segment> before, List<Segment> after) {
    }

    /** What happened to one paragraph. The four kinds of {@code ParagraphChange}. */
    public enum Kind {

        ADDED("added"),
        REMOVED("removed"),
        CHANGED("changed"),
        MOVED("moved");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One paragraph change, with both sides already marked.
     *
     * <p>{@code before} is empty for an addition and {@code after} for a removal, which
     * is what lets the template render all four kinds the same way: whichever side
     * exists is shown. A move carries its text on the {@code after} side unmarked --
     * nothing about it was rewritten, only where it sits.
     *
     * @param fromIndex position in the earlier revision, null when there was none
     * @param toIndex   position in the later revision, null when there is none
     */
    public record Paragraph(
            Kind kind,
            Integer fromIndex,
            Integer toIndex,
            List<Segment> before,
            List<Segment> after) {
    }

    /**
     * A diff of this document that can be opened, and whether it is the one on screen.
     */
    public record Pair(int from, int to, Instant fetchedAt, boolean current) {
    }
}
