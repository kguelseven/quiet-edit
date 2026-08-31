package org.korhan.quietedit.versioning;

/**
 * A {@link DocumentDiff} together with what it is a diff of.
 *
 * <p>The two revisions travel with the diff because the diff alone cannot be read:
 * "paragraph 4 changed" means nothing without knowing which two observations of which
 * article were compared, and a reader deciding whether an edit is worth attention
 * needs the two fetch timestamps as much as the text.
 *
 * @param from the earlier revision, {@code to} the later one -- the direction the
 *             diff was computed in
 */
public record RevisionDiff(Document document, DocumentVersion from, DocumentVersion to, DocumentDiff diff) {
}
