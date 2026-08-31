package org.korhan.quietedit.versioning;

import java.util.List;

/**
 * A document's whole history together with the document it belongs to.
 *
 * <p>The document travels with the list for the same reason it travels with a
 * {@link RevisionDiff}: a bare list of ordinals and hashes cannot be read without
 * knowing which article they are ordinals of, and the canonical URL is the only
 * thing in this system a person recognises an article by.
 *
 * @param revisions oldest first, and never empty -- identity is established by
 *                  writing the first version alongside the document, so a document
 *                  with no revision is a defect rather than a state to represent
 */
public record DocumentRevisions(Document document, List<DocumentVersion> revisions) {
}
