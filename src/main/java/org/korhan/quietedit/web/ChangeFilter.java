package org.korhan.quietedit.web;

import java.util.UUID;

/**
 * What the reader asked the listing to show. One record rather than four parameters
 * so that the view can render the form back with the current selection in it, which
 * is what makes a filtered listing linkable.
 *
 * @param feedId         one feed, or null for all of them
 * @param within         how far back to look
 * @param minRevisions   the fewest revisions a document must have to appear; floored
 *                       at two, because a document with one revision has not changed
 * @param rewrittenOnly  hide revisions that only added paragraphs; see
 *                       {@link org.korhan.quietedit.analysis.InPlaceEdit}
 */
public record ChangeFilter(UUID feedId, TimeWindow within, int minRevisions, boolean rewrittenOnly) {

    /** The fewest revisions worth asking for: below two nothing has changed. */
    public static final int MIN_REVISIONS = 2;

    public ChangeFilter {
        within = within == null ? TimeWindow.ALL : within;
        minRevisions = Math.max(MIN_REVISIONS, minRevisions);
    }
}
