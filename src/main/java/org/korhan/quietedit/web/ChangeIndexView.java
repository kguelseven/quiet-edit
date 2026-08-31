package org.korhan.quietedit.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the index page renders: the rows, the form that produced them, and what
 * the listing had to leave out.
 *
 * @param rows    documents that changed, most recently changed first
 * @param feeds   the choices for the feed filter, so the form can be rendered back
 * @param filter  the current selection, echoed so a filtered listing is linkable
 * @param hidden  how many rows the rewritten-only filter removed, reported rather than
 *                dropped silently: "no results" and "twelve results, all tickers" are
 *                different answers
 * @param capped  true when the query returned a full page, so there may be more
 */
public record ChangeIndexView(
        List<Row> rows,
        List<FeedOption> feeds,
        ChangeFilter filter,
        int hidden,
        boolean capped) {

    /**
     * One document in the listing.
     *
     * @param paragraphs paragraph count of the newest revision. Carried on purpose:
     *                   an unusually low count is what a paywalled excerpt or a ticker
     *                   that lost its entry bodies looks like, and this makes the
     *                   extraction limits visible while scrolling instead of under a
     *                   query
     * @param fromVersion the earlier ordinal of the pair the row links to, and
     *                    {@code toVersion} the later one. Named explicitly rather than
     *                    left to the diff page's defaults, so the link stays the same
     *                    diff after the next revision arrives
     */
    public record Row(
            UUID documentId,
            String title,
            String host,
            String canonicalUrl,
            int revisions,
            Instant lastChangedAt,
            int paragraphs,
            int fromVersion,
            int toVersion) {
    }

    public record FeedOption(UUID id, String name) {
    }
}
