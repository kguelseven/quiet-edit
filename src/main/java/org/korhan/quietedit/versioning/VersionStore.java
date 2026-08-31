package org.korhan.quietedit.versioning;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The permanent record of what a document said, and when.
 *
 * <h2>Append-only, in full</h2>
 * Every revision is stored whole -- its own title, its own paragraph list -- and
 * never as a delta against its predecessor. Two reasons. Deltas make any two
 * versions comparable only by replaying every revision between them, so the
 * question "how did this article read a week ago" would cost the whole chain; and a
 * chain is only as sound as its weakest link, where one lost or corrupted delta
 * makes every later revision unreadable. Full rows cost storage, which is the
 * cheapest thing here.
 *
 * <p>Nothing in this class updates or deletes a version, and since Flyway V4 nothing
 * else can either: the table rejects both outright. There is no retention limit and
 * no expiry -- an article edited in 2026 and questioned in 2031 needs the 2026 text,
 * and a store that had quietly dropped it would have no answer.
 *
 * <h2>When an observation becomes a revision</h2>
 * Only when its content hash differs from the <em>newest</em> stored revision. A
 * re-fetch of unchanged text is the overwhelmingly common case -- most documents are
 * re-checked many times per edit -- and writing a row for each of those would bury
 * the edits in noise and make the history unreadable for its one purpose.
 *
 * <p>Comparing against the newest revision only, rather than against the whole
 * history, is deliberate: history is a sequence of observations, and an article that
 * goes A, B, A really did change twice. Both moves are appended, so the third revision
 * repeats the first one's content hash -- which is why Flyway V5 relaxed V1's unique
 * {@code (document_id, content_hash)} to a plain index. A returning wording is the
 * clearest signal this system has that an edit was reconsidered, and a store that
 * refused to record it would be silent precisely where it matters most.
 *
 * <h2>The document counters</h2>
 * {@code versionCount} and {@code lastChangedAt} are written in the same transaction
 * as the version, so a reader can never see a version the counters do not know about
 * or a count that promises a row that is not there. {@code versionCount} is set to
 * the new revision's ordinal rather than incremented, which keeps it equal to the
 * highest {@code versionNumber} by construction instead of by hope.
 *
 * <p>{@code lastChangedAt} stays null for a document's first revision. A first
 * observation is not a change -- there was nothing for it to differ from -- and the
 * re-check policy reads a null as "never seen to change", which is the truth about a
 * document observed once.
 */
@Service
public class VersionStore {

    private final DocumentVersionRepository versions;
    private final DocumentRepository documents;
    private final ContentHasher hasher;

    public VersionStore(DocumentVersionRepository versions, DocumentRepository documents,
                        ContentHasher hasher) {
        this.versions = versions;
        this.documents = documents;
        this.hasher = hasher;
    }

    /**
     * Records one observation of a document, appending a revision when the text moved.
     *
     * <p>One short transaction per observation, for the same reason
     * {@link DocumentRegistry} uses one: a run spends its wall clock on the network,
     * and a transaction spanning it would pin a connection for all of it.
     *
     * @throws IllegalArgumentException if the document does not exist -- identity is
     *                                  established before versioning, so a missing
     *                                  document is a defect and not a data condition
     */
    @Transactional
    public Stored record(UUID documentId, Observation observation) {
        String contentHash = hasher.hash(observation.content());
        Optional<DocumentVersion> latest = versions.findFirstByDocumentIdOrderByVersionNumberDesc(documentId);

        if (latest.isPresent() && latest.get().getContentHash().equals(contentHash)) {
            return Stored.unchanged(latest.get());
        }
        return Stored.appended(append(documentId, observation, contentHash, latest.orElse(null)),
                latest.map(DocumentVersion::getId).orElse(null));
    }

    private DocumentVersion append(UUID documentId, Observation observation, String contentHash,
                                   DocumentVersion previous) {
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("No document " + documentId));

        int versionNumber = previous == null ? 1 : previous.getVersionNumber() + 1;
        DocumentVersion version = new DocumentVersion(
                documentId,
                versionNumber,
                observation.fetchedAt(),
                observation.content().paragraphs(),
                contentHash,
                observation.httpStatus());
        version.setPageTitle(observation.content().title());
        version.setFeedTitle(observation.feedTitle());
        version.setRawHtmlRef(observation.rawHtmlRef());
        version.setPublishedAt(observation.publishedAt());
        version.setPublishedAtExact(observation.publishedAtExact());
        version.setEncoding(observation.encoding());
        DocumentVersion saved = versions.save(version);

        document.setVersionCount(versionNumber);
        if (previous != null) {
            document.setLastChangedAt(observation.fetchedAt());
        }
        documents.save(document);
        return saved;
    }

    /** The document's whole history, oldest first. */
    @Transactional(readOnly = true)
    public List<DocumentVersion> history(UUID documentId) {
        return versions.findByDocumentIdOrderByVersionNumberAsc(documentId);
    }

    /**
     * One revision by its ordinal. Together with {@link #history} this is what makes
     * <em>any</em> two revisions comparable and not just adjacent ones: both are read
     * in full and independently, so nothing has to be replayed between them.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentVersion> version(UUID documentId, int versionNumber) {
        return versions.findByDocumentIdAndVersionNumber(documentId, versionNumber);
    }

    /** The newest revision, which is the one a fresh observation is compared against. */
    @Transactional(readOnly = true)
    public Optional<DocumentVersion> latest(UUID documentId) {
        return versions.findFirstByDocumentIdOrderByVersionNumberDesc(documentId);
    }

    /**
     * The revision that was current at {@code instant}: the newest observation not
     * later than it, or empty when the document had not been observed yet.
     *
     * <p>"Current" is by observation time, not publication time. The store knows when
     * it looked, never when the publisher pressed save, and answering with a
     * publication date would claim a precision nobody has.
     */
    @Transactional(readOnly = true)
    public Optional<DocumentVersion> asOf(UUID documentId, Instant instant) {
        return versions.findCurrentAt(documentId, instant, Limit.of(1)).stream().findFirst();
    }

    /**
     * What one observation did to a document's history.
     *
     * @param versionId      the revision this observation is now represented by: the
     *                       appended one, or the newest existing one when nothing was
     *                       written
     * @param previousVersionId the revision that was newest before an append, null when
     *                       this was the document's first or when nothing was written
     */
    public record Stored(
            UUID versionId,
            int versionNumber,
            String contentHash,
            VersionOutcome outcome,
            UUID previousVersionId) {

        static Stored appended(DocumentVersion version, UUID previousVersionId) {
            return new Stored(version.getId(), version.getVersionNumber(), version.getContentHash(),
                    VersionOutcome.APPENDED, previousVersionId);
        }

        static Stored unchanged(DocumentVersion latest) {
            return new Stored(latest.getId(), latest.getVersionNumber(), latest.getContentHash(),
                    VersionOutcome.UNCHANGED, null);
        }

        public boolean appended() {
            return outcome == VersionOutcome.APPENDED;
        }
    }
}
