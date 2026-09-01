package org.korhan.quietedit.web;

import org.korhan.quietedit.ingest.FeedCoverage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Everything the index page renders: the rows, the form that produced them, and what the
 * listing had to leave out.
 *
 * @param hidden   how many rows the rewritten-only filter removed, reported rather than
 *                 dropped silently: "no results" and "twelve results, all tickers" are
 *                 different answers
 * @param capped   true when the query returned a full page, so there may be more
 * @param coverage what each of the busiest feeds has produced in total. Deliberately
 *                 unfiltered: it answers "is anything arriving at all", which a figure
 *                 that moved with the filters could not
 */
public record ChangeIndexView(
        List<Row> rows,
        List<FeedOption> feeds,
        ChangeFilter filter,
        int hidden,
        boolean capped,
        List<FeedCoverage> coverage) {

    /**
     * @param paragraphs paragraph count of the newest revision. Carried because an
     *                   unusually low count is what a paywalled excerpt or a ticker that
     *                   lost its entry bodies looks like
     * @param fromVersion the earlier ordinal of the pair the row links to. Named explicitly
     *                    rather than left to the diff page's defaults, so the link stays
     *                    the same diff after the next revision arrives
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
