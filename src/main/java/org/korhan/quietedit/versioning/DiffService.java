package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Answers the questions a person has when looking for a silent edit: which articles
 * changed, which revisions one of them has, and what changed between two of those.
 *
 * <p>The revision listing is what makes the diff endpoint navigable: nothing else reveals
 * when a revision was fetched or what it hashed to.
 *
 * <p>Diffs are computed per request, never stored: both inputs are immutable and complete,
 * so recomputing can never disagree with the store while a stored diff can -- and would,
 * the moment a threshold in {@link DiffEngine} moves, which it did in the ticket that
 * added this class.
 *
 * <p>What the classifier writes to {@code change.diff_payload} is a different thing: a
 * record of the evidence a verdict was reached on, which has to survive a later change of
 * mind about the engine precisely because the verdict did not.
 *
 * <p>Nothing here writes: a diff endpoint that recorded what it had been asked for would
 * make the history depend on who looked at it.
 */
@Service
public class DiffService {

    /**
     * Not a policy number: the response carries a headline per row, and an uncapped limit
     * turns one request into a full table scan serialised into memory.
     */
    static final int MAX_LISTING_LIMIT = 200;

    private final DocumentRepository documents;
    private final DocumentVersionRepository versions;
    private final DiffEngine engine;

    public DiffService(DocumentRepository documents, DocumentVersionRepository versions, DiffEngine engine) {
        this.documents = documents;
        this.versions = versions;
        this.engine = engine;
    }

    /**
     * @param limit clamped rather than rejected, because a caller browsing by hand should
     *              get a page, not an error
     */
    @Transactional(readOnly = true)
    public List<DocumentHistorySummary> changedDocuments(int limit) {
        return documents.changedDocuments(Limit.of(Math.clamp(limit, 1, MAX_LISTING_LIMIT)));
    }

    /**
     * No limit and no paging, matching the repository: a history is bounded by how often
     * one article was really edited.
     *
     * @throws DiffUnavailableException if there is no document with that id. A document
     *                                 observed once is not an error here: one revision is a
     *                                 truthful history, and it is the diff that needs a pair
     */
    @Transactional(readOnly = true)
    public DocumentRevisions revisions(UUID documentId) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new DiffUnavailableException(
                        DiffUnavailableException.Reason.UNKNOWN_DOCUMENT, "No document " + documentId));
        return new DocumentRevisions(document, versions.findByDocumentIdOrderByVersionNumberAsc(documentId));
    }

    /**
     * The diff between two revisions of one document.
     *
     * <p>Both ordinals default to the question actually asked most -- {@code to} the newest
     * revision, {@code from} the one before it -- which works because version numbers are
     * contiguous from 1 by construction in {@link VersionStore}.
     *
     * <p>Any pair is allowed, reversed included: reading a diff backwards is a legitimate
     * question, and a version compared with itself yields an empty diff rather than a bad
     * request.
     *
     * @throws DiffUnavailableException if the document is unknown, either ordinal was never
     *                                 observed, or the document has no pair to compare
     */
    @Transactional(readOnly = true)
    public RevisionDiff diff(UUID documentId, Integer from, Integer to) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new DiffUnavailableException(
                        DiffUnavailableException.Reason.UNKNOWN_DOCUMENT, "No document " + documentId));

        int toNumber = to != null ? to : latestNumberOf(documentId);
        int fromNumber = from != null ? from : toNumber - 1;
        if (fromNumber < 1) {
            throw new DiffUnavailableException(DiffUnavailableException.Reason.NO_REVISION_PAIR,
                    "Document " + documentId + " has been observed once; there is no earlier revision"
                            + " to compare revision " + toNumber + " against");
        }

        // Resolved first so that an ordinal the caller actually named is the one reported unknown.
        DocumentVersion later = revision(documentId, toNumber);
        DocumentVersion earlier = revision(documentId, fromNumber);
        return new RevisionDiff(document, earlier, later, engine.diff(earlier, later));
    }

    private int latestNumberOf(UUID documentId) {
        return versions.findFirstByDocumentIdOrderByVersionNumberDesc(documentId)
                .map(DocumentVersion::getVersionNumber)
                // Not a data condition: identity is established by writing the first version.
                .orElseThrow(() -> new DiffUnavailableException(
                        DiffUnavailableException.Reason.NO_REVISION_PAIR,
                        "Document " + documentId + " has no stored revision"));
    }

    private DocumentVersion revision(UUID documentId, int versionNumber) {
        return versions.findByDocumentIdAndVersionNumber(documentId, versionNumber)
                .orElseThrow(() -> new DiffUnavailableException(
                        DiffUnavailableException.Reason.UNKNOWN_REVISION,
                        "Document " + documentId + " has no revision " + versionNumber));
    }
}
