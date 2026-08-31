package org.korhan.quietedit.versioning;

/**
 * One document that has been observed to change, together with its newest revision.
 *
 * <p>Carries the two entities rather than a flat projection because the caller needs
 * something no projection can compute in JPQL: how many paragraphs the newest
 * revision has. The paragraph list is a jsonb column, not an element collection, so
 * {@code size()} does not apply to it and counting it means having the row. That
 * count is not decoration -- it is what makes a paywalled excerpt or a ticker that
 * lost its entry bodies visible while scrolling a listing, instead of only under a
 * query.
 *
 * <p>{@link DocumentHistorySummary} stays the shape for the REST listing, which needs
 * no paragraph count and is better off not loading every newest revision's text.
 *
 * @param latestRevision the revision with the highest ordinal, reached through a
 *                       correlated {@code max} rather than through the denormalised
 *                       {@code versionCount}, so a drifted counter cannot hide a row
 */
public record ChangedDocumentRow(Document document, DocumentVersion latestRevision) {
}
