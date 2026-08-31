package org.korhan.quietedit.versioning;

import java.time.Instant;
import java.util.UUID;

/**
 * One document that has been observed to change, with just enough of its newest
 * revision to recognise it by.
 *
 * <p>A read projection, not an entity view: the caller of the listing wants to scan
 * for the article worth opening, so the headline of the current revision is carried
 * along and nothing else of the text is. Loading the documents and then their newest
 * versions would be the same information at N+1 queries.
 *
 * @param latestVersionNumber the ordinal of the newest revision, which is also the
 *                            {@code to} a diff defaults to
 * @param latestTitle         the page headline of that revision; null for a version
 *                            written before a page title could be extracted
 */
public record DocumentHistorySummary(
        UUID documentId,
        String canonicalUrl,
        UUID feedId,
        Instant firstSeenAt,
        Instant lastCheckedAt,
        Instant lastChangedAt,
        int versionCount,
        int latestVersionNumber,
        String latestTitle) {
}
