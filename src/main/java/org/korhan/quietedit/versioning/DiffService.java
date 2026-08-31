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
 * <p>The revision listing is what makes the diff endpoint navigable. Without it the
 * only way to find the pair worth opening is to diff every adjacent pair in turn,
 * because nothing else reveals when a revision was fetched or what it hashed to.
 *
 * <h2>Computed per request, not stored</h2>
 * A diff is a pure function of two immutable rows, and both rows are complete: the
 * version store keeps every revision whole, never as a delta. So recomputing a diff
 * can never disagree with the store, while a stored one can -- and would, the moment
 * a threshold in {@link DiffEngine} moves, which it did in the same ticket that added
 * this class. A stored diff is a cache of a function whose inputs never change,
 * bought at the price of a table, a migration and an invalidation rule; the only
 * thing it would buy back is time this does not spend. The work is two jsonb reads
 * and a Myers diff over a few dozen paragraphs.
 *
 * <p>That is not an argument against persistence in general. What the classifier
 * writes to {@code change.diff_payload} is a different thing: a record of the
 * evidence a verdict was reached on, which has to survive a later change of mind
 * about the engine precisely because the verdict did not. Storing diffs here would
 * pre-empt that shape and force the classifier's ticket to work around a cache.
 *
 * <h2>Reading, only</h2>
 * Nothing here writes. A diff endpoint that recorded what it had been asked for
 * would make the history depend on who looked at it.
 */
@Service
public class DiffService {

    /**
     * Ceiling on one page of the changed-document listing. Not a policy number: the
     * response carries a headline per row, and an uncapped limit turns one request
     * into a full table scan serialised into memory.
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
     * Documents with more than one observed revision, newest change first.
     *
     * @param limit how many rows to return; clamped to at least one and at most
     *              {@value #MAX_LISTING_LIMIT} rather than rejected, because a caller
     *              browsing by hand should get a page, not an error
     */
    @Transactional(readOnly = true)
    public List<DocumentHistorySummary> changedDocuments(int limit) {
        return documents.changedDocuments(Limit.of(Math.clamp(limit, 1, MAX_LISTING_LIMIT)));
    }

    /**
     * Every revision of one document, oldest first.
     *
     * <p>No limit and no paging, matching the repository: a history is bounded by how
     * often one article was really edited, which is a handful of rows and not a page
     * size. The rows themselves stay small because the response carries no version
     * text -- see {@code DiffController.Revision}.
     *
     * @throws DiffUnavailableException if there is no document with that id. A
     *                                 document observed only once is not an error
     *                                 here: one revision is a truthful history, and
     *                                 it is the diff that needs a pair, not this
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
     * <p>Both ordinals are optional, and the defaults answer the question that is
     * actually asked most: {@code to} defaults to the newest revision and {@code from}
     * to the one before {@code to}, so no arguments at all means "what changed last".
     * Version numbers are contiguous from 1 by construction in {@link VersionStore},
     * which is what makes {@code to - 1} a revision that exists rather than a guess.
     *
     * <p>Any pair is allowed, adjacent or not, and so is a reversed one: the diff is
     * directional and reading it backwards is a legitimate question ("what did this
     * paragraph say before"). A version compared with itself yields an empty diff,
     * which is the true answer rather than a bad request.
     *
     * @throws DiffUnavailableException if the document is unknown, either ordinal was
     *                                 never observed, or the document has been seen
     *                                 only once and so has no pair to compare
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

        // The later revision is resolved first so that an ordinal the caller actually
        // named is the one reported as unknown, rather than the earlier one this
        // derived from it.
        DocumentVersion later = revision(documentId, toNumber);
        DocumentVersion earlier = revision(documentId, fromNumber);
        return new RevisionDiff(document, earlier, later, engine.diff(earlier, later));
    }

    private int latestNumberOf(UUID documentId) {
        return versions.findFirstByDocumentIdOrderByVersionNumberDesc(documentId)
                .map(DocumentVersion::getVersionNumber)
                // A document with no version at all is not a data condition: identity is
                // established by writing the first version alongside it.
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
